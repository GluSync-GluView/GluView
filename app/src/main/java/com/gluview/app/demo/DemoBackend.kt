package com.gluview.app.demo

import com.gluview.app.BuildConfig
import com.gluview.app.data.remote.dto.AccessRequestDto
import com.gluview.app.data.remote.dto.AccessRequestFeatureStateDto
import com.gluview.app.data.remote.dto.AccessRequestFeatures
import com.gluview.app.data.remote.dto.AlertThresholdsDto
import com.gluview.app.data.remote.dto.CaregiverLinkViewDto
import com.gluview.app.data.remote.dto.DailyStatsDto
import com.gluview.app.data.remote.dto.FeatureFlagsDto
import com.gluview.app.data.remote.dto.GlucoseEntryDto
import com.gluview.app.data.remote.dto.HbA1cResultDto
import com.gluview.app.data.remote.dto.InviteResponseDto
import com.gluview.app.data.remote.dto.MonthlyStatsDto
import com.gluview.app.data.remote.dto.PagedResultDto
import com.gluview.app.data.remote.dto.PivotRowDto
import com.gluview.app.data.remote.dto.RegisterResponseDto
import com.gluview.app.data.remote.dto.SummaryCabecalhoDto
import com.gluview.app.data.remote.dto.SummaryReportDto
import com.gluview.app.data.remote.dto.SupportReportCreatedDto
import com.gluview.app.data.remote.dto.TirDayDto
import com.gluview.app.data.remote.dto.TirReportDto
import com.gluview.app.data.remote.dto.TokenResponseDto
import com.gluview.app.data.remote.dto.UpdateLatestDto
import com.gluview.app.data.remote.dto.UserProfileDto
import com.gluview.app.data.remote.dto.WearDataDto
import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Servidor falso do modo demonstração. Só entra no build `demo` ([BuildConfig.DEMO_MODE]): é o
 * primeiro interceptor dos clientes HTTP e responde ele mesmo, sem nunca tocar a rede. Assim o app
 * roda o caminho normal da nuvem (fonte GLUSYNC, seguidor, relatórios), com dados de [DemoGlucose].
 *
 * Por que um servidor falso, e não uma fonte de sensor falsa: nada é enviado a lugar nenhum (não há
 * servidor), o build demo tem pacote próprio (`.demo`, banco e preferências separados do app real) e
 * as telas do seguidor, que dependem da nuvem, também ganham dados.
 *
 * Login: qualquer e-mail e senha entram. A conta demo é paciente e seguidora de "Ana", uma paciente
 * fictícia com a mesma curva deslocada no tempo.
 */
class DemoBackend(private val agora: () -> Long = System::currentTimeMillis) : Interceptor {

    private val gson = Gson()
    private val zone: ZoneId get() = ZoneId.systemDefault()

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val rota = rota(req.url.encodedPath)
        val q = { nome: String -> req.url.queryParameter(nome) }
        val (codigo, corpo) = responder(req.method, rota, q)
        return Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(codigo)
            .message(if (codigo < 300) "OK" else "Not Found")
            .body((corpo ?: "").toResponseBody(JSON))
            .build()
    }

    /** (código HTTP, corpo JSON). Rotas sem tratamento: GET → 404, escrita → 200 `{}`. */
    internal fun responder(metodo: String, rota: String, q: (String) -> String?): Pair<Int, String?> {
        val agora = agora()
        fun ok(obj: Any) = 200 to gson.toJson(obj)
        val seg = rota.split('/')
        return when {
            metodo == "POST" && rota in ROTAS_TOKEN -> ok(token(agora))
            metodo == "POST" && rota == "auth/register" -> ok(RegisterResponseDto(EU, EMAIL))
            metodo == "GET" && rota == "auth/me" -> ok(UserProfileDto(EU, EMAIL, "Você", listOf("patient", "follower"), "2026-01-01T00:00:00Z"))
            metodo == "GET" && rota == "auth/webauthn/credentials" -> ok(emptyList<Any>())

            metodo == "GET" && rota == "glucose/wear" -> ok(wear(agora))
            metodo == "GET" && rota == "glucose/range" -> ok(range(q, agora))
            metodo == "GET" && rota == "glucose/pivot" -> ok(pivot(dias(q, 30), offset(q), agora))
            metodo == "GET" && rota == "glucose/stats/daily" -> ok(diarias(dias(q, 30), offset(q), agora))
            metodo == "GET" && rota == "report/tir" -> ok(tir(dias(q, 30), offset(q), agora))
            metodo == "GET" && rota == "report/summary" -> ok(resumo(q, offset(q), agora))

            metodo == "GET" && rota == "follow/links" -> ok(listOf(vinculoAna()))
            metodo == "POST" && rota == "follow/invite" -> ok(InviteResponseDto("demo-convite", "pending"))
            metodo == "GET" && seg.size == 4 && seg[0] == "follow" && seg[2] == "glucose" && seg[3] == "current" ->
                ok(entrada(DemoGlucose.ultimaLeitura(agora), offsetDe(seg[1])))
            metodo == "GET" && seg.size == 4 && seg[0] == "follow" && seg[2] == "glucose" && seg[3] == "range" -> {
                val to = q("to")?.toLongOrNull() ?: agora
                val from = q("from")?.toLongOrNull() ?: (to - TimeUnit.HOURS.toMillis(24))
                ok(serie(from, minOf(to, agora), offsetDe(seg[1])))
            }
            metodo == "GET" && rota == "alerts/thresholds" -> ok(AlertThresholdsDto(55f, 70f, 180f, 250f))

            metodo == "GET" && rota == "config/features" -> ok(FeatureFlagsDto())
            metodo == "GET" && rota == "access-requests/me/status" ->
                ok(listOf(AccessRequestFeatureStateDto(AccessRequestFeatures.CLOUD_SYNC, "approved")))
            metodo == "GET" && rota == "access-requests/me" -> ok(emptyList<Any>())
            metodo == "POST" && rota == "access-requests/feature" -> ok(AccessRequestDto("demo-pedido", AccessRequestFeatures.CLOUD_SYNC, "approved"))
            metodo == "POST" && rota == "support/reports" -> ok(SupportReportCreatedDto("demo"))

            metodo == "GET" && rota in ROTAS_LISTA_VAZIA -> ok(emptyList<Any>())
            metodo == "GET" && rota == "sensors/history/active" -> 204 to null
            metodo == "GET" && rota.endsWith("app/update/latest") -> ok(UpdateLatestDto(updateAvailable = false))

            metodo == "GET" -> 404 to """{"error":"demo"}"""
            else -> 200 to "{}"
        }
    }

    // ── Conta ────────────────────────────────────────────────────────────────

    private fun token(agora: Long): TokenResponseDto {
        val exp = Instant.ofEpochMilli(agora).plusSeconds(TimeUnit.DAYS.toSeconds(365))
        val b64 = { s: String -> Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray()) }
        val jwt = b64("""{"alg":"none","typ":"JWT"}""") + "." +
            b64("""{"sub":"$EU","email":"$EMAIL","exp":${exp.epochSecond}}""") + ".demo"
        return TokenResponseDto(jwt, "demo-refresh", "patient", exp.atOffset(java.time.ZoneOffset.UTC).toString(), listOf("patient", "follower"))
    }

    private fun vinculoAna() = CaregiverLinkViewDto(
        id = "demo-vinculo", patientId = ANA, patientEmail = "ana@exemplo.invalid", patientName = "Ana",
        followerId = EU, followerEmail = EMAIL, followerName = "Você", status = "accepted",
        initiatedBy = ANA, createdAt = "2026-01-01T00:00:00Z", respondedAt = "2026-01-01T00:05:00Z"
    )

    // ── Glicemia ─────────────────────────────────────────────────────────────

    // A conta demo usa a curva no horário real; "Ana" usa a mesma curva deslocada (outro desenho).
    private fun offsetDe(paciente: String?): Long = if (paciente == ANA) OFFSET_ANA else 0L
    private fun offset(q: (String) -> String?): Long = offsetDe(q("patientId"))
    private fun valor(t: Long, offset: Long) = DemoGlucose.mgdl(t + offset, zone)

    private fun entrada(t: Long, offset: Long) =
        GlucoseEntryDto(t, valor(t, offset), DemoGlucose.direcao(t + offset, zone), "GluView Demo")

    private fun serie(from: Long, to: Long, offset: Long) =
        DemoGlucose.serie(from, to).map { (t, _) -> entrada(t, offset) }

    private fun wear(agora: Long): WearDataDto {
        val t = DemoGlucose.ultimaLeitura(agora)
        val v = valor(t, 0)
        val delta = (v - valor(t - DemoGlucose.STEP_MS, 0)).roundToInt()
        val minutos = ((agora - t) / 60_000L).toInt()
        val hora = Instant.ofEpochMilli(t).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
        return WearDataDto(v, DemoGlucose.seta(DemoGlucose.direcao(t, zone)), hora, status(v), delta, false, minutos)
    }

    private fun range(q: (String) -> String?, agora: Long): PagedResultDto<GlucoseEntryDto> {
        val to = q("to")?.let(::instante) ?: agora
        val from = q("from")?.let(::instante) ?: (to - TimeUnit.HOURS.toMillis(24))
        var dados = serie(from, minOf(to, agora), 0)
        if (q("order") == "desc") dados = dados.reversed()
        val tamanho = q("pageSize")?.toIntOrNull()?.coerceAtLeast(1) ?: 2000
        val pagina = q("page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val paginas = maxOf(1, (dados.size + tamanho - 1) / tamanho)
        return PagedResultDto(pagina, tamanho, dados.size, paginas, dados.drop((pagina - 1) * tamanho).take(tamanho))
    }

    private fun instante(s: String): Long? =
        runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: s.toLongOrNull()

    private fun status(v: Double) = when {
        v < 55 -> "urgent_low"; v < 70 -> "low"; v > 250 -> "urgent_high"; v > 180 -> "high"; else -> "normal"
    }

    // ── Relatórios ───────────────────────────────────────────────────────────

    private fun dias(q: (String) -> String?, padrao: Int) = (q("dias")?.toIntOrNull() ?: padrao).coerceIn(1, 120)

    private fun leiturasDoDia(dia: LocalDate, offset: Long, agora: Long): List<Double> {
        val inicio = dia.atStartOfDay(zone).toInstant().toEpochMilli()
        val fim = minOf(dia.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1, agora)
        return DemoGlucose.serie(inicio, fim).map { (t, _) -> valor(t, offset) }
    }

    private fun diasAte(n: Int, agora: Long): List<LocalDate> {
        val hoje = Instant.ofEpochMilli(agora).atZone(zone).toLocalDate()
        return (n - 1 downTo 0).map { hoje.minusDays(it.toLong()) }
    }

    private fun hba1c(media: Double) = arred((media + 46.7) / 28.7, 1)

    private fun tir(n: Int, offset: Long, agora: Long): TirReportDto {
        val dias = diasAte(n, agora).map { d ->
            val l = leiturasDoDia(d, offset, agora)
            TirDayDto(d.toString(), pct(l) { it in 70.0..180.0 }, hba1c(l.average()), pct(l) { it < 70 }, pct(l) { it > 180 })
        }
        val todas = diasAte(n, agora).flatMap { leiturasDoDia(it, offset, agora) }
        return TirReportDto("$n dias", pct(todas) { it in 70.0..180.0 }, hba1c(todas.average()), dias)
    }

    private fun diarias(n: Int, offset: Long, agora: Long) = diasAte(n, agora).map { d ->
        val l = leiturasDoDia(d, offset, agora)
        val media = l.average()
        val desvio = sqrt(l.sumOf { (it - media) * (it - media) } / l.size)
        DailyStatsDto(d.toString(), arred(media, 1), l.min(), l.max(), arred(desvio, 1), l.size,
            l.count { it < 70 }, l.count { it > 180 }, pct(l) { it in 70.0..180.0 }, hba1c(media))
    }

    private fun pivot(n: Int, offset: Long, agora: Long) = diasAte(n, agora).map { d -> linhaPivot(d, offset, agora) }

    private fun linhaPivot(d: LocalDate, offset: Long, agora: Long): PivotRowDto {
        val inicio = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val faixa = { h: Int ->
            val l = DemoGlucose.serie(inicio + h * HORA, minOf(inicio + (h + 3) * HORA - 1, agora)).map { (t, _) -> valor(t, offset) }
            if (l.isEmpty()) 0.0 else arred(l.average(), 1)
        }
        val todas = leiturasDoDia(d, offset, agora)
        return PivotRowDto(d.toString(), d.year, d.monthValue, faixa(0), faixa(3), faixa(6), faixa(9), faixa(12),
            faixa(15), faixa(18), faixa(21), arred(todas.average(), 1), todas.size, hba1c(todas.average()))
    }

    private fun resumo(q: (String) -> String?, offset: Long, agora: Long): SummaryReportDto {
        val hoje = Instant.ofEpochMilli(agora).atZone(zone).toLocalDate()
        val mes = q("mes")?.toIntOrNull() ?: hoje.monthValue
        val ano = q("ano")?.toIntOrNull() ?: hoje.year
        val primeiro = LocalDate.of(ano, mes, 1)
        val diasMes = generateSequence(primeiro) { it.plusDays(1) }
            .takeWhile { it.monthValue == mes && !it.isAfter(hoje) }.toList()
        val l90 = diasAte(90, agora).flatMap { leiturasDoDia(it, offset, agora) }
        val media90 = l90.average()
        val cab = SummaryCabecalhoDto(
            periodo = "%02d/%d".format(mes, ano),
            gerado = Instant.ofEpochMilli(agora).toString(),
            totalDias = diasMes.size,
            hbA1c90dias = HbA1cResultDto("90 dias", arred(media90, 1), hba1c(media90), "Bom", l90.size, "good")
        )
        val lMes = diasMes.flatMap { leiturasDoDia(it, offset, agora) }
        val mensal = if (lMes.isEmpty()) emptyList() else listOf(
            MonthlyStatsDto(ano, mes, arred(lMes.average(), 1), hba1c(lMes.average()), pct(lMes) { it in 70.0..180.0 }, diasMes.size, lMes.size)
        )
        return SummaryReportDto(cab, diasMes.map { linhaPivot(it, offset, agora) }, mensal)
    }

    private fun pct(l: List<Double>, cond: (Double) -> Boolean) =
        if (l.isEmpty()) 0.0 else arred(100.0 * l.count(cond) / l.size, 1)

    private fun arred(v: Double, casas: Int): Double {
        if (v.isNaN()) return 0.0
        val f = Math.pow(10.0, casas.toDouble())
        return (v * f).roundToInt() / f
    }

    companion object {
        const val EU = "demo-voce"
        const val ANA = "demo-ana"
        const val EMAIL = "demo@gluview.app"
        private const val HORA = 3_600_000L
        private val OFFSET_ANA = TimeUnit.MINUTES.toMillis(157)
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val ROTAS_TOKEN = setOf(
            "auth/login", "auth/refresh", "auth/verify-email", "auth/reset-password", "auth/webauthn/authenticate/verify"
        )
        private val ROTAS_LISTA_VAZIA = setOf(
            "sensors/history", "apikeys", "integrations", "watchfaces/projects", "watchfaces/store"
        )
        private val RAIZES = listOf(
            "auth", "glucose", "report", "follow", "alerts", "config", "access-requests", "terms", "sensors",
            "apikeys", "watchfaces", "integrations", "debug", "support", "donations", "app"
        )

        /** Caminho relativo à API ("glucose/wear"), com ou sem o prefixo /api/glusync/v1. */
        internal fun rota(caminho: String): String {
            val partes = caminho.trim('/').split('/')
            val i = partes.indexOfFirst { it in RAIZES }
            return if (i < 0) caminho.trim('/') else partes.drop(i).joinToString("/")
        }

        /** O interceptor, só no build demo; nos outros builds, nada. */
        fun seAtivo(): Interceptor? = if (BuildConfig.DEMO_MODE) DemoBackend() else null
    }
}
