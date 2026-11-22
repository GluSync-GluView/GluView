package com.gluview.app.mirror

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.gluview.app.BuildConfig
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.notification.NotificationHelper
import com.gluview.app.util.AppLogger
import com.gluview.app.util.withAppLanguage
import com.gluview.mirror.FollowerAlertKind
import com.gluview.mirror.FollowerAlerts
import com.gluview.mirror.FollowerState
import com.gluview.mirror.LinkStatus
import com.gluview.mirror.LocalHttpServer
import com.gluview.mirror.MirrorCandidate
import com.gluview.mirror.MirrorClient
import com.gluview.mirror.MirrorCrypto
import com.gluview.mirror.MirrorMeta
import com.gluview.mirror.MirrorReading
import com.gluview.mirror.MirrorServer
import com.gluview.mirror.MirrorThresholds
import com.gluview.mirror.PairingCode
import com.gluview.mirror.StunClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/** O que a tela mostra de cada pessoa seguida pelo espelho. */
data class FollowedView(
    val pairIdHex: String,
    val name: String,
    val latest: MirrorReading?,
    val link: LinkStatus,
    val lastAliveMs: Long,
    val address: String?,
    val lastError: String?,
    val thresholds: MirrorThresholds,
    val revoked: Boolean = false,
    /** Limiar de "sem sinal" escolhido para esta pessoa (alertas). */
    val noSignalMin: Int = 20
)

/** Estado dos servidores deste celular (para a tela). */
data class MirrorServerStatus(
    val shareRunning: Boolean = false,
    val connectedFollowers: Set<String> = emptySet(),
    val httpRunning: Boolean = false,
    val wifiAddress: String? = null,
    val error: String? = null,
    /** F2.4: endereços que o seguidor tenta, na ordem (rede local → IPv6 → público → STUN). */
    val candidates: List<MirrorCandidate> = emptyList()
)

/**
 * Plano 21, F2 — liga o núcleo do espelho (:mirror-core) ao app. Vive enquanto o GluSyncService
 * vive ([start]/[stop]). Paciente: servidor do espelho + servidor web local + anúncio na rede.
 * Seguidor: um cliente por pessoa seguida, com alertas próprios (link caído, sem leitura, faixas).
 *
 * Regra: só compartilha a glicemia DESTE celular — no modo seguidor do GluSync o celular mostra a
 * glicemia de outra pessoa, que não é repassada adiante.
 */
@Singleton
class MirrorManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: MirrorStore,
    private val preferences: AppPreferences,
    private val repository: GlucoseRepository,
    private val notifications: NotificationHelper,
    private val appLogger: AppLogger
) {
    private class Followed(@Volatile var cfg: FollowedPatient, val client: MirrorClient) {
        val state = FollowerState()
        val alerts = FollowerAlerts()
        @Volatile var meta: MirrorMeta? = null
        @Volatile var lastAlive = 0L
        @Volatile var lastError: String? = null
        @Volatile var lastAddress: String? = null
        /** O paciente tirou o acesso: sem alarmes nem novas tentativas até a pessoa remover ou parear de novo. */
        @Volatile var revoked = false
    }

    private val followed = ConcurrentHashMap<String, Followed>()
    private var shareServer: MirrorServer? = null
    private var shareConfig: Pair<Int, Boolean>? = null
    private var httpServer: LocalHttpServer? = null
    private var httpConfig: MirrorSettings? = null
    private var scope: CoroutineScope? = null
    private var jobs = mutableListOf<Job>()
    private val discovered = CopyOnWriteArrayList<String>()
    private var nsdRegistration: NsdManager.RegistrationListener? = null
    private var nsdDiscovery: NsdManager.DiscoveryListener? = null
    @Volatile private var lastPublished = 0L
    /** F2.4: IPv4 público pelo STUN (tentativa extra) e quando foi descoberto. */
    @Volatile private var stunIpv4: String? = null
    @Volatile private var stunAtMs = 0L

    private val _followedViews = MutableStateFlow<List<FollowedView>>(emptyList())
    val followedViews: StateFlow<List<FollowedView>> = _followedViews
    private val _status = MutableStateFlow(MirrorServerStatus())
    val status: StateFlow<MirrorServerStatus> = _status

    private val nsd get() = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (this.scope != null) return
        this.scope = scope
        jobs += scope.launch(Dispatchers.IO) {
            store.load()
            store.settings.collect { runCatching { reconcile(it) }.onFailure { e -> log("Falha ao aplicar ajustes", e) } }
        }
        jobs += scope.launch(Dispatchers.IO) {
            while (isActive) { runCatching { tickAlerts() }; refreshViews(); delay(30_000L) }
        }
        // F2.4: renova o IP público pelo STUN a cada 10 min enquanto o compartilhamento estiver ligado.
        jobs += scope.launch(Dispatchers.IO) {
            while (isActive) { runCatching { refreshStun() }; delay(60_000L) }
        }
    }

    private fun refreshStun(force: Boolean = false) {
        val s = store.settings.value
        if (shareServer == null || !s.stunEnabled) {
            if (stunIpv4 != null) { stunIpv4 = null; refreshStatus() }
            return
        }
        if (!force && System.currentTimeMillis() - stunAtMs < 10 * 60_000L) return
        stunAtMs = System.currentTimeMillis()
        val ip = StunClient.publicIpv4()
        if (ip != null || stunIpv4 != null) appLogger.usage("MIRROR", "STUN", mapOf("ok" to (ip != null)))
        stunIpv4 = ip
        refreshStatus()
    }

    /**
     * F2.4: por onde o paciente pode ser alcançado agora, na ordem de tentativa: rede local (Wi-Fi),
     * IPv6 global, endereço público informado e, por último, o IP público do STUN.
     */
    fun patientCandidates(s: MirrorSettings = store.settings.value): List<MirrorCandidate> {
        val port = s.sharePort
        val list = mutableListOf<MirrorCandidate>()
        wifiIpv4()?.let { list += MirrorCandidate(MirrorCandidate.Kind.LAN, it, port) }
        globalIpv6().take(2).forEach { list += MirrorCandidate(MirrorCandidate.Kind.IPV6, it, port) }
        s.publicHost.trim().takeIf(MirrorCandidate::validHost)?.let { list += MirrorCandidate(MirrorCandidate.Kind.PUBLIC, it, port) }
        if (s.stunEnabled) stunIpv4?.let { list += MirrorCandidate(MirrorCandidate.Kind.STUN, it, port) }
        return MirrorCandidate.ordered(list)
    }

    @Synchronized
    fun stop() {
        jobs.forEach { it.cancel() }; jobs.clear()
        scope = null
        followed.values.forEach { it.client.stop() }; followed.clear()
        shareServer?.stop(); shareServer = null; shareConfig = null
        httpServer?.stop(); httpServer = null; httpConfig = null
        stopNsd()
        _status.value = MirrorServerStatus()
    }

    /** Nova leitura deste celular (chamado pelo serviço, só fora do modo seguidor). */
    fun publish(reading: GlucoseReading) {
        val server = shareServer ?: return
        if (reading.epochMs <= 0L || reading.isStale || reading.epochMs <= lastPublished) return
        lastPublished = reading.epochMs
        server.publish(MirrorReading(reading.epochMs, reading.sgvMgDl, arrowToDirection(reading.arrow, reading.direction), reading.delta))
    }

    /** Paciente: novo seguidor. Devolve o código (QR) para o outro celular ler. */
    fun createPairing(label: String): PairingCode {
        val id = MirrorCrypto.newPairId(); val key = MirrorCrypto.newKey()
        val s = store.update { it.copy(shared = it.shared + SharedFollower(MirrorCrypto.hex(id), MirrorCrypto.b64(key),
            label.trim(), System.currentTimeMillis()), shareEnabled = true) }
        return PairingCode(id, key, wifiIpv4().orEmpty(), s.sharePort, s.patientName.ifBlank { context.getString(R.string.mirror_default_patient) },
            extraCandidates(s))
    }

    /** Endereços além do Wi-Fi (que já vai no campo principal do código). */
    private fun extraCandidates(s: MirrorSettings): List<MirrorCandidate> =
        patientCandidates(s).filterNot { it.kind == MirrorCandidate.Kind.LAN }

    /** Código de um seguidor já criado (para mostrar o QR de novo). */
    fun pairingFor(f: SharedFollower): PairingCode? {
        val s = store.load()
        return PairingCode(MirrorCrypto.unhex(f.pairIdHex) ?: return null, MirrorCrypto.unb64(f.keyB64) ?: return null,
            wifiIpv4().orEmpty(), s.sharePort, s.patientName.ifBlank { context.getString(R.string.mirror_default_patient) },
            extraCandidates(s))
    }

    fun revoke(pairIdHex: String) {
        store.update { s -> s.copy(shared = s.shared.filterNot { it.pairIdHex == pairIdHex }) }
        MirrorCrypto.unhex(pairIdHex)?.let { shareServer?.revoke(it) }
    }

    /** Seguidor: adiciona a pessoa do código lido. */
    fun follow(code: PairingCode) {
        val hex = MirrorCrypto.hex(code.pairId)
        store.update { s -> s.copy(followed = s.followed.filterNot { it.pairIdHex == hex } +
            FollowedPatient(hex, MirrorCrypto.b64(code.key), code.patientName, code.host, code.port,
                learned = code.extra.map { it.encode() })) }
    }

    fun unfollow(pairIdHex: String) = store.update { s -> s.copy(followed = s.followed.filterNot { it.pairIdHex == pairIdHex }) }

    fun reconnectAll() = followed.values.forEach { it.client.reconnectNow() }

    // ------------------------------------------------------------------ interno

    private suspend fun reconcile(s: MirrorSettings) {
        val follower = preferences.isFollowerOnly.first()
        // Servidor do espelho (paciente).
        val wantShare = s.shareEnabled && !follower
        val shareKey = s.sharePort to wantShare
        if (shareKey != shareConfig) {
            shareServer?.stop(); shareServer = null; stopNsd()
            if (wantShare) {
                val srv = MirrorServer(
                    port = s.sharePort,
                    keyFor = { id -> store.settings.value.shared.firstOrNull { it.pairIdHex == MirrorCrypto.hex(id) }
                        ?.keyB64?.let(MirrorCrypto::unb64) },
                    history = { since -> runBlocking { history(since) } },
                    meta = { runBlocking { meta() } },
                    candidates = { patientCandidates() },
                    listener = object : MirrorServer.Listener {
                        override fun onFollowerConnected(pairIdHex: String, remote: String) = refreshStatus()
                        override fun onFollowerDisconnected(pairIdHex: String, reason: String) = refreshStatus()
                        override fun onError(message: String) { appLogger.usage("MIRROR", message) }
                    }
                )
                runCatching { srv.start() }.onSuccess { shareServer = srv; registerNsd(s.sharePort); kickStun() }
                    .onFailure { e -> setError(context.getString(R.string.mirror_err_port, s.sharePort)); log("Falha ao abrir o espelho", e) }
            }
            shareConfig = shareKey
        }
        // Servidor web local.
        // Na rede Wi-Fi a senha é obrigatória, com 12+ caracteres (quem tem a senha lê a glicemia).
        val httpWanted = s.httpEnabled && (!s.httpOnWifi || s.httpSecret.length >= 12)
        val httpKey = s.copy(shared = emptyList(), followed = emptyList(), shareEnabled = false, patientName = "")
            .takeIf { httpWanted }
        if (httpKey != httpConfig) {
            httpServer?.stop(); httpServer = null
            if (httpWanted) {
                val srv = LocalHttpServer(s.httpPort, !s.httpOnWifi, s.httpSecret.ifBlank { null }, httpData())
                runCatching { srv.start() }.onSuccess { httpServer = srv }
                    .onFailure { e -> setError(context.getString(R.string.mirror_err_port, s.httpPort)); log("Falha ao abrir o servidor local", e) }
            }
            httpConfig = httpKey
        }
        // Clientes (seguidor).
        val wanted = s.followed.associateBy { it.pairIdHex }
        followed.keys.filter { it !in wanted }.forEach { followed.remove(it)?.client?.stop() }
        wanted.values.forEach { cfg ->
            val cur = followed[cfg.pairIdHex]
            val sameLink = cur != null && cur.cfg.revoked == cfg.revoked && cur.cfg.keyB64 == cfg.keyB64 && cur.cfg.host == cfg.host && cur.cfg.port == cfg.port
            if (cur == null || !sameLink) {
                cur?.client?.stop()
                startClient(cfg, cur)
            } else {
                cur.cfg = cfg // só mudaram alertas/nome: não derruba a conexão
            }
        }
        if (s.followed.any { it.host.isBlank() }) startDiscovery() else stopDiscovery()
        // F2.4: ligou o STUN (ou ainda não tem IP público): descobre já, sem segurar os ajustes.
        if (shareServer != null && s.stunEnabled && stunIpv4 == null) kickStun()
        refreshStatus(); refreshViews()
    }

    private fun kickStun() { scope?.launch(Dispatchers.IO) { runCatching { refreshStun(force = true) } } }

    private fun startClient(cfg: FollowedPatient, previous: Followed?) {
        val key = MirrorCrypto.unb64(cfg.keyB64) ?: return
        val id = MirrorCrypto.unhex(cfg.pairIdHex) ?: return
        var holder: Followed? = null
        var rr = 0
        val client = MirrorClient(
            pairId = id, key = key,
            // F2.4: primeiro a rede (endereço do código ou achado na rede local), depois os endereços
            // que o paciente mandou (IPv6, público informado) e, por último, o do STUN.
            addresses = {
                val cur = holder?.cfg ?: cfg
                val out = LinkedHashSet<InetSocketAddress>()
                if (cur.host.isNotBlank()) runCatching { InetSocketAddress(cur.host, cur.port) }.getOrNull()?.let(out::add)
                if (discovered.isNotEmpty()) {
                    val start = rr++ % discovered.size
                    (discovered.drop(start) + discovered.take(start)).forEach { h ->
                        runCatching { InetSocketAddress(h, cur.port) }.getOrNull()?.let(out::add)
                    }
                }
                MirrorCandidate.ordered(cur.learned.mapNotNull(MirrorCandidate::decode))
                    .forEach { c -> c.socketAddress()?.let(out::add) }
                out.toList()
            },
            since = { holder?.state?.lastEpoch() ?: 0L },
            listener = object : MirrorClient.Listener {
                override fun onLinkAddress(address: InetSocketAddress) { holder?.lastAddress = "${address.hostString}:${address.port}" }
                override fun onConnected(meta: MirrorMeta) {
                    holder?.meta = meta; holder?.lastError = null; refreshViews()
                    // F2.4: guarda os endereços atuais do paciente para quando sair da rede de casa.
                    val learned = meta.candidates.map { it.encode() }
                    if (learned.isNotEmpty() && learned != (holder?.cfg ?: cfg).learned) {
                        store.update { s -> s.copy(followed = s.followed.map { if (it.pairIdHex == cfg.pairIdHex) it.copy(learned = learned) else it }) }
                    }
                }
                override fun onReadings(items: List<MirrorReading>) {
                    holder?.state?.merge(items, System.currentTimeMillis()); refreshViews()
                    scope?.launch(Dispatchers.IO) { runCatching { tickAlerts() } }
                }
                override fun onAlive(nowMs: Long) { holder?.lastAlive = nowMs }
                override fun onDisconnected(reason: String) { holder?.lastError = friendlyError(reason); refreshViews() }
                override fun onRevoked() {
                    holder?.revoked = true
                    store.update { s -> s.copy(followed = s.followed.map { if (it.pairIdHex == cfg.pairIdHex) it.copy(revoked = true) else it }) }
                    holder?.lastError = context.getString(R.string.mirror_revoked); refreshViews()
                    notify(cfg, context.getString(R.string.mirror_revoked), FollowerAlertKind.LINK_DOWN, urgent = false)
                }
            }
        )
        val f = Followed(cfg, client).also { it.revoked = cfg.revoked }
        previous?.let { p -> f.state.merge(p.state.all(), System.currentTimeMillis()); f.meta = p.meta }
        holder = f
        followed[cfg.pairIdHex] = f
        if (cfg.revoked) f.lastError = context.getString(R.string.mirror_revoked) else client.start()
    }

    private suspend fun tickAlerts() {
        val now = System.currentTimeMillis()
        for (f in followed.values) {
            if (f.revoked) continue
            val th = f.meta?.thresholds ?: MirrorThresholds()
            val kind = f.alerts.evaluate(now, f.state.latest(), f.lastAlive, th, f.cfg.alerts) ?: continue
            val c = context.withAppLanguage(preferences.appLanguage.first())
            val name = f.meta?.patientName?.ifBlank { null } ?: f.cfg.name
            val v = f.state.latest()?.sgvMgDl?.let { Math.round(it).toString() }
            val msg = when (kind) {
                FollowerAlertKind.LINK_DOWN -> c.getString(R.string.mirror_alert_link_down, f.cfg.alerts.linkTimeoutMin)
                FollowerAlertKind.NO_SENSOR_DATA -> c.getString(R.string.mirror_alert_no_data)
                FollowerAlertKind.URGENT_LOW -> c.getString(R.string.mirror_alert_urgent_low, v)
                FollowerAlertKind.LOW -> c.getString(R.string.mirror_alert_low, v)
                FollowerAlertKind.HIGH -> c.getString(R.string.mirror_alert_high, v)
                FollowerAlertKind.URGENT_HIGH -> c.getString(R.string.mirror_alert_urgent_high, v)
            }
            notify(f.cfg, msg, kind, FollowerAlerts.isUrgent(kind), name)
        }
    }

    private fun notify(cfg: FollowedPatient, message: String, kind: FollowerAlertKind, urgent: Boolean, name: String = cfg.name) {
        val id = 7_000 + (cfg.pairIdHex.hashCode() and 0x3ff)
        notifications.showAlertNotification(name.ifBlank { context.getString(R.string.mirror_title) }, message,
            if (urgent) NotificationHelper.CHANNEL_ALARM else NotificationHelper.CHANNEL_ALERT, id)
        appLogger.usage("MIRROR", "Alerta do seguidor", mapOf("tipo" to kind.name))
    }

    private fun refreshViews() {
        val now = System.currentTimeMillis()
        _followedViews.value = store.settings.value.followed.mapNotNull { cfg ->
            val f = followed[cfg.pairIdHex] ?: return@mapNotNull null
            FollowedView(cfg.pairIdHex, f.meta?.patientName?.ifBlank { null } ?: cfg.name, f.state.latest(),
                FollowerState.linkStatus(now, f.lastAlive, cfg.alerts.linkTimeoutMin), f.lastAlive, f.lastAddress,
                f.lastError, f.meta?.thresholds ?: MirrorThresholds(), f.revoked, cfg.alerts.noSignalMin)
        }
    }

    private fun refreshStatus() {
        _status.value = _status.value.copy(
            shareRunning = shareServer != null,
            connectedFollowers = shareServer?.connectedPairs ?: emptySet(),
            httpRunning = httpServer != null,
            wifiAddress = wifiIpv4(),
            error = if (shareServer == null && httpServer == null) _status.value.error else null,
            candidates = if (shareServer != null) runCatching { patientCandidates() }.getOrDefault(emptyList()) else emptyList()
        )
    }

    fun recentHttpRequests(): List<LocalHttpServer.RequestLog> = httpServer?.recentRequests().orEmpty()

    /** Erro de rede/protocolo em palavras simples (o detalhe técnico vai só para o log). */
    private fun friendlyError(reason: String): String {
        appLogger.usage("MIRROR", "Conexão do seguidor caiu", mapOf("motivo" to reason.take(200)))
        val r = reason.lowercase()
        return context.getString(when {
            "desafio" in r || "quadro" in r || "tag mismatch" in r || "aead" in r || "versão" in r -> R.string.mirror_err_protocol
            "refused" in r || "unreach" in r || "no route" in r || "timed out" in r || "timeout" in r ||
                "failed to connect" in r || "unknownhost" in r || "unable to resolve" in r -> R.string.mirror_err_unreachable
            else -> R.string.mirror_err_dropped
        })
    }

    private fun setError(msg: String) { _status.value = _status.value.copy(error = msg) }

    private suspend fun history(sinceMs: Long): List<MirrorReading> =
        repository.getRecentReadings(sinceMs).filter { !it.isStale }.map {
            MirrorReading(it.dateMs, it.sgvMgDl, arrowToDirection(it.arrow, it.direction), it.delta)
        }

    private suspend fun meta(): MirrorMeta {
        val t = preferences.glucoseThresholds.first()
        val name = store.settings.value.patientName.ifBlank { context.getString(R.string.mirror_default_patient) }
        return MirrorMeta(name, MirrorThresholds(t.urgentLow.toDouble(), t.low.toDouble(), t.high.toDouble(), t.urgentHigh.toDouble()),
            preferences.glucoseUnit.first())
    }

    private fun httpData() = object : LocalHttpServer.DataSource {
        override fun readings(count: Int, sinceMs: Long?): List<MirrorReading> = runBlocking {
            if (preferences.isFollowerOnly.first()) emptyList()
            else history(sinceMs ?: (System.currentTimeMillis() - 24 * 3_600_000L)).sortedByDescending { it.epochMs }.take(count)
        }
        override fun thresholds(): MirrorThresholds = runBlocking { meta().thresholds }
        override fun unit(): String = runBlocking { preferences.glucoseUnit.first() }
        override fun version(): String = BuildConfig.VERSION_NAME
    }

    // ---- rede local

    fun wifiIpv4(): String? = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.asSequence().mapNotNull { cm.getLinkProperties(it) }
            .flatMap { it.linkAddresses.asSequence() }.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    /** IPv6 globais deste celular (alcançáveis de fora quando a rede não bloqueia a entrada). */
    fun globalIpv6(): List<String> = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.asSequence().mapNotNull { cm.getLinkProperties(it) }
            .flatMap { it.linkAddresses.asSequence() }.map { it.address }
            .filter { it is Inet6Address && MirrorCandidate.isGlobalIpv6(it) }
            .map(MirrorCandidate::hostOf).distinct().toList()
    }.getOrDefault(emptyList())

    private fun registerNsd(port: Int) {
        val info = NsdServiceInfo().apply { serviceName = "GluView"; serviceType = NSD_TYPE; this.port = port }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { appLogger.usage("MIRROR", "Anúncio na rede falhou", mapOf("code" to code)) }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); nsdRegistration = l }
    }

    @Synchronized
    private fun startDiscovery() {
        if (nsdDiscovery != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) {}
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            @Suppress("DEPRECATION")
            override fun onServiceFound(i: NsdServiceInfo) {
                runCatching {
                    nsd.resolveService(i, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(s: NsdServiceInfo, code: Int) {}
                        override fun onServiceResolved(s: NsdServiceInfo) {
                            val h = s.host?.hostAddress ?: return
                            if (h !in discovered) discovered.add(h)
                            followed.values.filter { it.cfg.host.isBlank() }.forEach { it.client.reconnectNow() }
                        }
                    })
                }
            }
        }
        runCatching { nsd.discoverServices(NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, l); nsdDiscovery = l }
    }

    @Synchronized
    private fun stopDiscovery() {
        nsdDiscovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        nsdDiscovery = null
    }

    private fun stopNsd() {
        nsdRegistration?.let { runCatching { nsd.unregisterService(it) } }
        nsdRegistration = null
    }

    private fun log(msg: String, e: Throwable) =
        appLogger.error("MIRROR", msg, mapOf("erro" to (e.message ?: e::class.simpleName.orEmpty())))

    companion object {
        const val NSD_TYPE = "_gluview._tcp."

        /** Direção Nightscout a partir do que o app guarda (direção, ou só a seta). */
        fun arrowToDirection(arrow: String, direction: String): String {
            if (direction in NS_DIRECTIONS) return direction
            return when (arrow) {
                "↑↑" -> "DoubleUp"; "↑" -> "SingleUp"; "↗" -> "FortyFiveUp"; "→" -> "Flat"
                "↘" -> "FortyFiveDown"; "↓" -> "SingleDown"; "↓↓" -> "DoubleDown"; else -> "NONE"
            }
        }
        private val NS_DIRECTIONS = setOf("DoubleUp", "SingleUp", "FortyFiveUp", "Flat", "FortyFiveDown", "SingleDown", "DoubleDown")
    }
}
