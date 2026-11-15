package com.gluview.app.data.dexcom

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.gluview.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PT-3 — GATT do Dexcom G7. Porte do fluxo G7 do `Ob1G5StateMachine` do xDrip (GPLv3):
 *
 *  1. Conecta no G7 já pareado pelo app oficial neste celular (nome `DXCM…` na lista de pareados),
 *     com autoConnect: o G7 só aceita conexão quando anuncia, a cada ~5 min.
 *  2. Liga notificação na ExtraData e indicação na Authentication.
 *  3. Autentica pelo [DexcomG7Auth] (EC-JPAKE com o código de 4 dígitos, libkeks do xDrip). Com a
 *     chave salva da conexão anterior, pula as rodadas.
 *  4. Liga notificação na Control e na Backfill; pede a glicemia (0x4E). O G7 responde 0x4E em
 *     claro na Control. Para buracos, pede histórico (0x59): registros chegam na Backfill e o fim
 *     vem como 0x59 na Control.
 *  5. O G7 desconecta sozinho; o autoConnect reconecta no próximo anúncio.
 *
 * Toda operação de GATT passa por uma fila (uma de cada vez, a próxima só no callback da anterior),
 * como o Android exige. SEM TESTE COM SENSOR REAL.
 */
@Singleton
class DexcomG7BleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger
) {
    interface Listener {
        fun onConnectionChanged(connected: Boolean)
        fun onAuthenticated(sharedKey: ByteArray?)
        fun onReading(packet: ByteArray)
        fun onBackfill(stream: ByteArray)
        fun onNeedsOfficialPairing()
        fun onWrongCode()
        fun onRssi(rssi: Int) {}
    }

    private companion object {
        const val LOG = "DEXCOM_G7_BLE"
        const val OP_TIMEOUT_MS = 5_000L
    }

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var listener: Listener? = null
    @Volatile private var auth: DexcomG7Auth? = null
    @Volatile private var pairingCode: String = ""
    @Volatile private var savedKey: ByteArray? = null
    @Volatile private var authenticated = false
    private val backfillBuffer = ByteArrayOutputStream()

    fun setListener(l: Listener?) { listener = l }

    /** Código de pareamento (4 dígitos) e a chave da última conexão, se houver. */
    fun configure(code: String, key: ByteArray?) {
        pairingCode = code
        savedKey = key
    }

    /** G7 pareados neste celular (pelo app oficial): nome começa com "DXCM". */
    @SuppressLint("MissingPermission")
    fun bondedG7s(): List<Pair<String, String>> = runCatching {
        adapter?.bondedDevices.orEmpty()
            .filter { (it.name ?: "").startsWith("DXCM") }
            .map { (it.name ?: "") to it.address }
    }.getOrDefault(emptyList())

    @SuppressLint("MissingPermission")
    fun connect(mac: String) {
        val a = adapter ?: run { appLogger.error(LOG, "BluetoothAdapter==null"); return }
        close()
        val device = runCatching { a.getRemoteDevice(mac) }.getOrNull() ?: run {
            appLogger.error(LOG, "MAC inválido", mapOf("mac" to mac)); return
        }
        appLogger.usage(LOG, "Conectando (autoConnect)", mapOf("mac" to mac))
        gatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        close()
        listener?.onConnectionChanged(false)
    }

    @SuppressLint("MissingPermission")
    private fun close() {
        handler.removeCallbacksAndMessages(null)
        synchronized(queue) { queue.clear(); busy = false }
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        auth = null
        authenticated = false
        backfillBuffer.reset()
    }

    /** Pede histórico entre dois instantes do relógio do sensor (só depois de autenticar). */
    fun requestBackfill(startDexTime: Int, endDexTime: Int) {
        if (!authenticated || startDexTime >= endDexTime) return
        backfillBuffer.reset()
        enqueue(Op.Write(DexcomGatt.CONTROL, DexcomG7Tx.backfill(startDexTime, endDexTime), noResponse = false))
    }

    // ── Fila de GATT ──────────────────────────────────────────────────────────

    private sealed class Op {
        data class Enable(val uuid: UUID, val indication: Boolean) : Op()
        data class Write(val uuid: UUID, val bytes: ByteArray, val noResponse: Boolean) : Op()
        object CreateBond : Op()
        /** Pausa (o xDrip espaça os pedaços em 40 ms e espera 500 ms antes do comando). */
        data class Delay(val ms: Long) : Op()
    }

    private val queue = ArrayDeque<Op>()
    @Volatile private var busy = false

    private fun enqueue(vararg ops: Op) {
        synchronized(queue) { ops.forEach { queue.add(it) } }
        pump()
    }

    private fun done() {
        handler.removeCallbacksAndMessages(null)
        synchronized(queue) { busy = false }
        pump()
    }

    @SuppressLint("MissingPermission")
    private fun pump() {
        val op = synchronized(queue) {
            if (busy) return
            queue.poll()?.also { busy = true }
        } ?: return
        val g = gatt ?: run { synchronized(queue) { busy = false; queue.clear() }; return }
        handler.postDelayed({ appLogger.error(LOG, "Operação GATT sem resposta", mapOf("op" to op.javaClass.simpleName)); done() }, OP_TIMEOUT_MS)
        val ok = when (op) {
            is Op.Enable -> enable(g, op.uuid, op.indication)
            is Op.Write -> write(g, op.uuid, op.bytes, op.noResponse)
            Op.CreateBond -> { runCatching { g.device.createBond() }; handler.post { done() }; true }
            is Op.Delay -> { handler.removeCallbacksAndMessages(null); handler.postDelayed({ done() }, op.ms); true }
        }
        if (!ok) { appLogger.error(LOG, "Operação GATT recusada", mapOf("op" to op.javaClass.simpleName)); done() }
    }

    private fun characteristic(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? =
        g.getService(DexcomGatt.CGM_SERVICE)?.getCharacteristic(uuid)

    @SuppressLint("MissingPermission")
    private fun enable(g: BluetoothGatt, uuid: UUID, indication: Boolean): Boolean {
        val c = characteristic(g, uuid) ?: return false
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(DexcomGatt.CCCD) ?: return false
        val value = if (indication) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") run { cccd.value = value; g.writeDescriptor(cccd) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun write(g: BluetoothGatt, uuid: UUID, bytes: ByteArray, noResponse: Boolean): Boolean {
        val c = characteristic(g, uuid) ?: return false
        val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, bytes, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") run { c.writeType = type; c.value = bytes; g.writeCharacteristic(c) }
        }
    }

    // ── Autenticação ──────────────────────────────────────────────────────────

    private fun apply(ops: List<G7AuthOp>) {
        ops.forEach { op ->
            when (op) {
                is G7AuthOp.WriteExtraData -> enqueue(Op.Write(DexcomGatt.EXTRA_DATA, op.chunk, noResponse = true), Op.Delay(40))
                is G7AuthOp.WriteAuth -> {
                    if (ops.any { it is G7AuthOp.WriteExtraData }) enqueue(Op.Delay(500))
                    enqueue(Op.Write(DexcomGatt.AUTHENTICATION, op.command, noResponse = false))
                }
                G7AuthOp.CreateBond -> enqueue(Op.CreateBond)
                is G7AuthOp.Authenticated -> {
                    authenticated = true
                    appLogger.usage(LOG, "G7 autenticado")
                    listener?.onAuthenticated(op.sharedKey)
                    enqueue(
                        Op.Enable(DexcomGatt.CONTROL, indication = false),
                        Op.Enable(DexcomGatt.BACKFILL, indication = false),
                        Op.Write(DexcomGatt.CONTROL, DexcomG7Tx.eGlucose(), noResponse = false)
                    )
                }
                G7AuthOp.NeedsOfficialPairing -> {
                    appLogger.error(LOG, "G7 sem pareamento neste celular — o primeiro pareamento é pelo app oficial")
                    listener?.onNeedsOfficialPairing()
                }
                G7AuthOp.WrongCode -> {
                    appLogger.error(LOG, "Código de pareamento do G7 não confere")
                    listener?.onWrongCode()
                }
                is G7AuthOp.Restart -> appLogger.error(LOG, "Autenticação do G7 falhou", mapOf("motivo" to op.reason))
            }
        }
    }

    // ── Callback ──────────────────────────────────────────────────────────────

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            appLogger.usage(LOG, "Estado GATT mudou", mapOf("newState" to newState, "gattStatus" to status))
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener?.onConnectionChanged(true)
                if (!g.discoverServices()) appLogger.error(LOG, "discoverServices() falhou")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // Não fecha o gatt: com autoConnect, o Android reconecta no próximo anúncio do G7.
                handler.removeCallbacksAndMessages(null)
                synchronized(queue) { queue.clear(); busy = false }
                authenticated = false
                auth = null
                listener?.onConnectionChanged(false)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || g.getService(DexcomGatt.CGM_SERVICE)?.getCharacteristic(DexcomGatt.EXTRA_DATA) == null) {
                appLogger.error(LOG, "Serviço CGM do G7 ausente", mapOf("status" to status))
                return
            }
            if (!DexcomG7Auth.isValidPairingCode(pairingCode)) {
                appLogger.error(LOG, "Código de pareamento do G7 ausente ou inválido")
                return
            }
            auth = DexcomG7Auth(jamorham.keks.Plugin.getInstance(pairingCode)).also { it.loadSavedKey(savedKey) }
            enqueue(
                Op.Enable(DexcomGatt.EXTRA_DATA, indication = false),
                Op.Enable(DexcomGatt.AUTHENTICATION, indication = true)
            )
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val uuid = descriptor.characteristic.uuid
            if (status != BluetoothGatt.GATT_SUCCESS) appLogger.error(LOG, "CCCD falhou", mapOf("uuid" to uuid, "status" to status))
            done()
            // As duas assinaturas da autenticação prontas: começa o JPAKE.
            if (uuid == DexcomGatt.AUTHENTICATION && status == BluetoothGatt.GATT_SUCCESS) {
                auth?.let { apply(it.onReady()) }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) appLogger.error(LOG, "Escrita falhou", mapOf("uuid" to c.uuid, "status" to status))
            done()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            dispatch(c.uuid, value)

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) c.value?.let { dispatch(c.uuid, it) }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) listener?.onRssi(rssi)
        }
    }

    private fun dispatch(uuid: UUID, value: ByteArray) {
        when (uuid) {
            DexcomGatt.AUTHENTICATION -> auth?.let { apply(it.onAuthentication(value)) }
            DexcomGatt.EXTRA_DATA -> auth?.let { apply(it.onExtraData(value)) }
            DexcomGatt.BACKFILL -> synchronized(backfillBuffer) { backfillBuffer.write(value) }
            DexcomGatt.CONTROL -> when (value.firstOrNull()?.toInt()?.and(0xFF)) {
                DexcomG7Data.OPCODE_EGLUCOSE -> listener?.onReading(value)
                DexcomG7Data.OPCODE_BACKFILL_CONTROL -> {
                    val stream = synchronized(backfillBuffer) { backfillBuffer.toByteArray().also { backfillBuffer.reset() } }
                    if (stream.isNotEmpty()) listener?.onBackfill(stream)
                }
                else -> appLogger.usage(LOG, "Mensagem da Control não tratada", mapOf("opcode" to (value.firstOrNull() ?: 0)))
            }
        }
    }
}
