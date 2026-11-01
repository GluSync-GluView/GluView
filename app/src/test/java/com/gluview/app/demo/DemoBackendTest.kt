package com.gluview.app.demo

import com.gluview.app.data.remote.GluSyncApi
import com.gluview.app.data.remote.UpdateApi
import com.gluview.app.data.remote.dto.LoginRequestDto
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** O servidor falso do build demo, exercitado pela própria [GluSyncApi] (mesmos DTOs do app). */
class DemoBackendTest {

    private val zona = ZoneId.of("America/Sao_Paulo")
    private val agora = ZonedDateTime.of(2026, 11, 1, 14, 37, 0, 0, zona).toInstant().toEpochMilli()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://demo.gluview.invalid/")
        .client(OkHttpClient.Builder().addInterceptor(DemoBackend { agora }).build())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    private val api = retrofit.create(GluSyncApi::class.java)

    @Test fun `curva e deterministica e fica numa faixa plausivel`() {
        val dia = DemoGlucose.serie(agora - TimeUnit.DAYS.toMillis(1), agora, zona).map { it.second }
        assertEquals(DemoGlucose.mgdl(agora, zona), DemoGlucose.mgdl(agora, zona), 0.0)
        assertTrue(dia.all { it in 72.0..230.0 })
        assertTrue("curva sem variação", dia.max() - dia.min() > 60)
        assertEquals(288, dia.size) // 24h a cada 5 min (14:37 não cai na grade)
    }

    @Test fun `qualquer login entra e o perfil e paciente e seguidor`() = runBlocking {
        val token = api.login(LoginRequestDto("qualquer@x.com", "123"))
        assertEquals(3, token.accessToken.split('.').size)
        val me = api.getMe()
        assertEquals(DemoBackend.EU, me.id)
        assertTrue(me.roles.containsAll(listOf("patient", "follower")))
    }

    @Test fun `leitura atual bate com a curva`() = runBlocking {
        val wear = api.getWearData()
        val t = DemoGlucose.ultimaLeitura(agora)
        assertEquals(DemoGlucose.mgdl(t), wear.sgv, 0.0)
        assertFalse(wear.stale)
        assertTrue(wear.arrow in setOf("↑↑", "↑", "↗", "→", "↘", "↓", "↓↓"))
    }

    @Test fun `range respeita o intervalo, a ordem e a paginacao`() = runBlocking {
        val de = agora - TimeUnit.HOURS.toMillis(3)
        val r = api.getRange(from = java.time.Instant.ofEpochMilli(de).toString(), to = java.time.Instant.ofEpochMilli(agora).toString())
        assertTrue(r.data.isNotEmpty())
        assertTrue(r.data.all { it.date in de..agora })
        assertEquals(r.data.sortedBy { it.date }, r.data)
        val pag = api.getRange(from = java.time.Instant.ofEpochMilli(de).toString(), pageSize = 10, page = 2)
        assertEquals(10, pag.data.size)
        assertEquals(2, pag.page)
    }

    @Test fun `seguidor ve a Ana com curva propria`() = runBlocking {
        val links = api.listFollowLinks()
        assertEquals(1, links.size)
        val ana = links.single()
        assertEquals("accepted", ana.status)
        assertTrue(ana.iAmFollower(DemoBackend.EU))
        val atual = api.followedPatientCurrent(ana.patientId)
        val faixa = api.followedPatientRange(ana.patientId, agora - TimeUnit.HOURS.toMillis(6), agora)
        assertEquals(72, faixa.size)
        assertEquals(atual.date, faixa.last().date)
        val minha = api.getRange(from = java.time.Instant.ofEpochMilli(agora - TimeUnit.HOURS.toMillis(6)).toString()).data
        assertTrue("Ana igual à conta demo", minha.map { it.sgv } != faixa.map { it.sgv })
    }

    @Test fun `relatorios tem um dia por dia pedido`() = runBlocking {
        val tir = api.getTirReport(dias = 14)
        assertEquals(14, tir.dias.size)
        assertTrue(tir.mediaTir in 50.0..100.0)
        assertEquals(30, api.getDailyStats().size)
        assertEquals(7, api.getPivot(dias = 7).size)
        val resumo = api.getSummaryReport()
        assertEquals("11/2026", resumo.cabecalho.periodo)
        assertEquals(1, resumo.tabelaPivot.size)
    }

    @Test fun `nuvem liberada, sem atualizacao e rotas desconhecidas dao 404`() = runBlocking {
        assertEquals("approved", api.getAccessRequestStatus().single().state)
        assertFalse(retrofit.create(UpdateApi::class.java).latest(1L).updateAvailable)
        val erro = runCatching { api.getAlertPreferences() }.exceptionOrNull()
        assertTrue(erro is HttpException && erro.code() == 404)
        assertTrue(api.acceptTerms(com.gluview.app.data.remote.dto.AcceptTermsDto(version = 3, documentHash = "x", locale = "pt")).isSuccessful)
    }

    @Test fun `rota ignora o prefixo da API`() {
        assertEquals("glucose/wear", DemoBackend.rota("/api/glusync/v1/glucose/wear"))
        assertEquals("glucose/wear", DemoBackend.rota("/glucose/wear"))
        assertEquals("follow/demo-ana/glucose/range", DemoBackend.rota("/follow/demo-ana/glucose/range"))
        assertEquals("app/update/latest", DemoBackend.rota("/api/glusync/v1/app/update/latest"))
    }
}
