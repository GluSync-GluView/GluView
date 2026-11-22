package com.gluview.mirror

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors

/**
 * Plano 21, F2.1 — servidor web local no celular, compatível com o que relógios e apps já leem do
 * xDrip/Juggluco (porta 17580) e com o básico da API Nightscout v1 (só leitura):
 * `/sgv.json`, `/api/v1/entries[.json]`, `/api/v1/entries/sgv.json`, `/api/v1/treatments[.json]`,
 * `/api/v1/devicestatus[.json]`, `/status.json`, `/api/v1/status[.json]`, `/pebble` e `/`.
 * Mais `/watch.json` (próprio do GluView): o que os mostradores de relógio precisam para o gráfico
 * igual ao da Mi Band, compacto (cabe no limite de dados em segundo plano do Garmin).
 *
 * Segurança: fora do próprio celular ([localOnly] = false) a senha é obrigatória — quem chamar
 * manda `api-secret` (a senha ou o SHA-1 dela, como no Nightscout) no cabeçalho, ou `?token=`.
 * Só GET; nada escreve no app.
 */
class LocalHttpServer(
    private val port: Int,
    private val localOnly: Boolean,
    private val secret: String?,
    private val data: DataSource,
    private val clock: () -> Long = System::currentTimeMillis
) {
    interface DataSource {
        /** Leituras mais novas primeiro, no máximo [count]. */
        fun readings(count: Int, sinceMs: Long?): List<MirrorReading>
        fun treatments(count: Int): List<Map<String, Any?>> = emptyList()
        fun deviceStatus(count: Int): List<Map<String, Any?>> = emptyList()
        fun thresholds(): MirrorThresholds
        fun unit(): String
        fun name(): String = "GluView"
        fun version(): String = ""
    }

    data class RequestLog(val atMs: Long, val remote: String, val path: String, val status: Int)

    private val log = ConcurrentLinkedDeque<RequestLog>()
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "gluview-http").apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null

    val boundPort: Int get() = server?.localPort ?: -1
    fun recentRequests(): List<RequestLog> = log.toList()

    init {
        require(localOnly || !secret.isNullOrBlank()) { "senha obrigatória fora do próprio celular" }
    }

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        val bind = if (localOnly) InetAddress.getLoopbackAddress() else null
        s.bind(InetSocketAddress(bind, port))
        server = s
        Thread({
            while (!s.isClosed) {
                val sock = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { handle(sock) }
            }
        }, "gluview-http-accept").apply { isDaemon = true }.start()
    }

    fun stop() { runCatching { server?.close() }; server = null; pool.shutdownNow() }

    private fun handle(sock: Socket) = sock.use {
        sock.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        val resp = if (parts.size < 2) Response(400, "text/plain", "bad request")
        else route(parts[0], parts[1], headers)
        val body = resp.body.toByteArray(Charsets.UTF_8)
        val out = sock.getOutputStream()
        out.write(("HTTP/1.1 ${resp.status} ${reason(resp.status)}\r\n" +
            "Content-Type: ${resp.type}; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        out.write(body); out.flush()
        val path = parts.getOrNull(1)?.substringBefore('?').orEmpty()
        log.addFirst(RequestLog(clock(), sock.inetAddress?.hostAddress.orEmpty(), path, resp.status))
        while (log.size > 30) log.pollLast()
    }

    data class Response(val status: Int, val type: String, val body: String)

    /** Roteamento puro (testável sem socket). */
    fun route(method: String, target: String, headers: Map<String, String>): Response {
        if (method != "GET" && method != "HEAD") return Response(405, "text/plain", "only GET")
        val path = target.substringBefore('?').trimEnd('/').ifEmpty { "/" }
        val query = parseQuery(target.substringAfter('?', ""))
        if (!authorized(headers, query)) return Response(401, "application/json", Json.write(mapOf("status" to 401, "message" to "Unauthorized")))
        val count = query["count"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 24
        val since = query["find[date][\$gt]"]?.toLongOrNull() ?: query["find[date][\$gte]"]?.toLongOrNull()
        return when (path) {
            "/sgv.json", "/api/v1/entries", "/api/v1/entries.json", "/api/v1/entries/sgv.json", "/api/v1/entries/sgv" ->
                json(data.readings(count, since).sortedByDescending { it.epochMs }.take(count).map(::entry))
            "/api/v1/entries/current", "/api/v1/entries/current.json" ->
                json(data.readings(1, null).take(1).map(::entry))
            "/api/v1/treatments", "/api/v1/treatments.json" -> json(data.treatments(count))
            "/api/v1/devicestatus", "/api/v1/devicestatus.json" -> json(data.deviceStatus(count))
            "/status.json", "/api/v1/status", "/api/v1/status.json" -> json(status())
            "/pebble" -> json(pebble(query["count"]?.toIntOrNull()?.coerceIn(1, 10) ?: 1))
            "/watch.json" -> json(watch(query["minutes"]?.toIntOrNull()?.coerceIn(30, 720) ?: 180,
                query["step"]?.toIntOrNull()?.coerceIn(1, 15) ?: 5))
            "/" -> Response(200, "text/html", page())
            else -> Response(404, "application/json", Json.write(mapOf("status" to 404, "message" to "Not found")))
        }
    }

    private fun json(v: Any?) = Response(200, "application/json", Json.write(v))

    private fun authorized(headers: Map<String, String>, query: Map<String, String>): Boolean {
        val s = secret?.takeIf { it.isNotBlank() } ?: return localOnly
        val given = headers["api-secret"] ?: query["token"] ?: query["secret"] ?: return false
        return constantEquals(given, s) || constantEquals(given.lowercase(), sha1(s))
    }

    private fun constantEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun entry(r: MirrorReading): Map<String, Any?> = linkedMapOf(
        "_id" to "gv${r.epochMs}",
        "type" to "sgv",
        "sgv" to Math.round(r.sgvMgDl).toInt(),
        "direction" to r.direction,
        "date" to r.epochMs,
        "dateString" to Instant.ofEpochMilli(r.epochMs).toString(),
        "delta" to r.deltaMgDl,
        "device" to "GluView"
    )

    private fun status(): Map<String, Any?> {
        val t = data.thresholds()
        return linkedMapOf(
            "status" to "ok", "name" to data.name(), "version" to data.version(), "serverTime" to Instant.ofEpochMilli(clock()).toString(),
            "serverTimeEpoch" to clock(), "apiEnabled" to true,
            "settings" to linkedMapOf("units" to if (data.unit() == "MMOL_L") "mmol" else "mg/dl",
                "thresholds" to linkedMapOf("bgHigh" to t.urgentHigh, "bgTargetTop" to t.high,
                    "bgTargetBottom" to t.low, "bgLow" to t.urgentLow))
        )
    }

    private fun pebble(n: Int): Map<String, Any?> {
        val list = data.readings(n + 1, null).sortedByDescending { it.epochMs }
        val bgs = list.take(n).mapIndexed { i, r ->
            val prev = list.getOrNull(i + 1)
            val mmol = data.unit() == "MMOL_L"
            val sgv = if (mmol) String.format(java.util.Locale.US, "%.1f", r.sgvMgDl / 18.0) else Math.round(r.sgvMgDl).toString()
            val delta = prev?.let { r.sgvMgDl - it.sgvMgDl } ?: r.deltaMgDl?.toDouble() ?: 0.0
            linkedMapOf("sgv" to sgv, "trend" to trend(r.direction), "direction" to r.direction, "datetime" to r.epochMs,
                "bgdelta" to if (mmol) String.format(java.util.Locale.US, "%.1f", delta / 18.0) else Math.round(delta).toString())
        }
        return linkedMapOf("status" to listOf(mapOf("now" to clock())), "bgs" to bgs, "cals" to emptyList<Any>())
    }

    /**
     * Para os mostradores (Garmin, Zepp OS): última leitura já formatada na unidade do usuário, os
     * limites do paciente e os pontos das últimas [minutes] min (no máximo um a cada [step] min,
     * sempre com a mais nova), em `pts` = [idadeMin, mg/dL, idadeMin, mg/dL, ...] do mais velho ao
     * mais novo. Idades pela hora do CELULAR: relógio com hora errada não engana o "há X min".
     */
    private fun watch(minutes: Int, step: Int): Map<String, Any?> {
        val now = clock()
        val since = now - minutes * 60_000L
        val list = data.readings(1000, since).filter { it.epochMs in (since + 1)..(now + 60_000L) }.sortedByDescending { it.epochMs }
        val latest = list.firstOrNull()
        val prev = list.getOrNull(1)
        val mmol = data.unit() == "MMOL_L"
        val picked = ArrayList<MirrorReading>()
        var lastBucket = Long.MIN_VALUE
        for (r in list) {   // do mais novo ao mais velho: um por intervalo de [step] min
            val bucket = (now - r.epochMs).coerceAtLeast(0L) / (step * 60_000L)
            if (bucket != lastBucket) { picked += r; lastBucket = bucket }
        }
        val pts = ArrayList<Any>()
        picked.asReversed().forEach { r ->
            pts += ((now - r.epochMs).coerceAtLeast(0L) / 60_000L).toInt()
            pts += Math.round(r.sgvMgDl).toInt()
        }
        val t = data.thresholds()
        val delta = latest?.let { l -> prev?.takeIf { l.epochMs - it.epochMs <= 15 * 60_000L }?.let { l.sgvMgDl - it.sgvMgDl } ?: l.deltaMgDl?.toDouble() }
        return linkedMapOf(
            "now" to now,
            "unit" to data.unit(),
            "th" to linkedMapOf("ul" to Math.round(t.urgentLow).toInt(), "l" to Math.round(t.low).toInt(),
                "h" to Math.round(t.high).toInt(), "uh" to Math.round(t.urgentHigh).toInt()),
            "sgv" to latest?.let { Math.round(it.sgvMgDl).toInt() },
            "txt" to latest?.let { if (mmol) String.format(java.util.Locale.US, "%.1f", it.sgvMgDl / 18.0) else Math.round(it.sgvMgDl).toString() },
            "age" to latest?.let { ((now - it.epochMs).coerceAtLeast(0L) / 60_000L).toInt() },
            "dir" to (latest?.direction ?: "NONE"),
            "dtxt" to delta?.let { d ->
                if (mmol) String.format(java.util.Locale.US, "%+.1f", d / 18.0) else (if (d >= 0) "+" else "") + Math.round(d)
            },
            "pts" to pts
        )
    }

    private fun trend(d: String) = when (d) {
        "DoubleUp" -> 1; "SingleUp" -> 2; "FortyFiveUp" -> 3; "Flat" -> 4
        "FortyFiveDown" -> 5; "SingleDown" -> 6; "DoubleDown" -> 7; else -> 0
    }

    private fun page(): String {
        val r = data.readings(1, null).firstOrNull()
        val v = r?.let { "${Math.round(it.sgvMgDl)} mg/dL ${directionToArrow(it.direction)}" } ?: "—"
        val age = r?.let { "${(clock() - it.epochMs) / 60_000} min" } ?: ""
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">" +
            "<meta http-equiv=\"refresh\" content=\"60\"><title>GluView</title></head>" +
            "<body style=\"font-family:sans-serif;text-align:center;background:#111;color:#eee\">" +
            "<h1 style=\"font-size:64px\">${escape(v)}</h1><p>${escape(age)}</p></body></html>"
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error"
    }

    companion object {
        const val DEFAULT_PORT = 17580

        fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { String.format("%02x", it) }

        fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate { p ->
            val i = p.indexOf('=')
            val k = if (i < 0) p else p.substring(0, i)
            val v = if (i < 0) "" else p.substring(i + 1)
            runCatching { URLDecoder.decode(k, "UTF-8") }.getOrDefault(k) to runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }
    }
}
