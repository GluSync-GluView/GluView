package com.gluview.app.watchface.apply

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Cliente BLE para Mi Band / Huami (Mi Band 5/6) — CAMADA 1: conexão + handshake de autenticação.
 *
 * Handshake clássico Huami (char de auth 0x0009 no serviço fee1), com a auth key de 32 hex já
 * conhecida (obtida do Zepp/Gadgetbridge — não é o 1º pareamento):
 *   1. habilita notificações na char de auth;
 *   2. escreve {0x02,0x00} = pedir número aleatório;
 *   3. recebe {0x10,0x02,0x01,<16 bytes>};
 *   4. cifra os 16 bytes com a auth key (AES/ECB/NoPadding);
 *   5. escreve {0x03,0x00}+cifrado;
 *   6. recebe {0x10,0x03,0x01} = autenticado (…,0x04 = falha).
 *
 * É o alicerce do upload de visor (camadas 2/3 virão depois, só se isto autenticar).
 */
@Singleton
class MiBandClient @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "MiBandHuami"

    @SuppressLint("MissingPermission")
    suspend fun connectAndAuthenticate(
        mac: String,
        key: ByteArray,
        log: (String) -> Unit
    ): Boolean = suspendCancellableCoroutine { cont ->
        fun emit(msg: String) { Log.i(tag, msg); log(msg) }

        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            emit("Bluetooth desligado/indisponível.")
            if (cont.isActive) cont.resume(false)
            return@suspendCancellableCoroutine
        }

        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            emit("MAC inválido: $mac")
            if (cont.isActive) cont.resume(false)
            return@suspendCancellableCoroutine
        }

        var gatt: BluetoothGatt? = null
        var authChar: BluetoothGattCharacteristic? = null
        var finished = false
        fun finish(result: Boolean) {
            if (finished) return
            finished = true
            try { gatt?.disconnect() } catch (_: Exception) {}
            if (cont.isActive) cont.resume(result)
        }

        fun writeAuth(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray) {
            @Suppress("DEPRECATION")
            run {
                // Usa o tipo de escrita que a característica suporta (with response vs no response).
                ch.writeType = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ch.value = data
                val queued = g.writeCharacteristic(ch)
                Log.i(tag, "TX ${data.joinToString(" ") { "%02x".format(it) }} (enfileirado=$queued, type=${ch.writeType})")
            }
        }

        fun handlePacket(g: BluetoothGatt, ch: BluetoothGattCharacteristic, v: ByteArray) {
            emit("RX (${v.size}B): ${v.joinToString(" ") { "%02x".format(it) }}")
            if (v.size < 3 || v[0].toInt() != 0x10) return
            when {
                v[1].toInt() == 0x02 && v[2].toInt() == 0x01 -> {
                    val random = v.copyOfRange(3, v.size)
                    emit("Número aleatório recebido (${random.size} bytes), cifrando…")
                    try {
                        val enc = aesEncrypt(random, key)
                        writeAuth(g, ch, byteArrayOf(0x03, 0x00) + enc)
                    } catch (e: Exception) {
                        emit("Erro ao cifrar (key tem 16 bytes?): ${e.message}")
                        finish(false)
                    }
                }
                v[1].toInt() == 0x03 && v[2].toInt() == 0x01 -> {
                    emit("✅ Autenticado com sucesso na Mi Band!")
                    finish(true)
                }
                v[1].toInt() == 0x03 -> {
                    emit("❌ Autenticação recusada (auth key incorreta?). Resposta=${v.joinToString(" ") { "%02x".format(it) }}")
                    finish(false)
                }
            }
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        emit("Conectado (status=$status). Descobrindo serviços…")
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        emit("Desconectado (status=$status).")
                        finish(false)
                        try { g.close() } catch (_: Exception) {}
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val service = g.getService(UUID.fromString(MiBand6WatchFaceApplier.HUAMI_SERVICE_FEE1))
                authChar = service?.getCharacteristic(UUID.fromString(MiBand6WatchFaceApplier.CHARACTERISTIC_AUTH))
                val ch = authChar
                if (ch == null) {
                    emit("Característica de autenticação (fee1/0x0009) não encontrada. Serviços=${g.services.map { it.uuid.toString().take(8) }}")
                    finish(false)
                    return
                }
                val props = ch.properties
                val isIndicate = props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                emit("Char auth: props=0x${props.toString(16)} [notify=${props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0}, indicate=$isIndicate, write=${props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0}, wNoResp=${props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0}]")
                g.setCharacteristicNotification(ch, true)
                val ccc = ch.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                if (ccc == null) {
                    emit("Descriptor CCCD ausente na char de auth.")
                    finish(false)
                    return
                }
                @Suppress("DEPRECATION")
                run {
                    // Habilita o modo que a característica realmente expõe (indicate ou notify).
                    ccc.value = if (isIndicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(ccc)
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                emit("Notificações habilitadas (status=$status). Pedindo número aleatório…")
                authChar?.let { writeAuth(g, it, byteArrayOf(0x02, 0x00)) }
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                Log.i(tag, "onCharacteristicWrite status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) emit("⚠️ Escrita falhou (status=$status).")
            }

            // Android 13+ (API 33)
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
                handlePacket(g, ch, value)
            }

            // Compat < API 33
            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                handlePacket(g, ch, ch.value ?: ByteArray(0))
            }
        }

        emit("Conectando em $mac…")
        gatt = device.connectGatt(context, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        cont.invokeOnCancellation {
            try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        }
    }

    private fun aesEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }
}
