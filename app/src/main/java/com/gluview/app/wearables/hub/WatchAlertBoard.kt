package com.gluview.app.wearables.hub

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plano 21, F7.1 — quadro de avisos dos apps de relógio que BUSCAM os dados (Garmin, Zepp OS): o alerta fica
 * aqui e sai no próximo /watch.json?app=… daquele app, que vibra ao ver um id novo. Expira em 15 min
 * (um aviso velho chegando depois engana) e some com a soneca.
 */
@Singleton
class WatchAlertBoard @Inject constructor() {
    data class Entry(val id: Long, val kind: String, val title: String, val body: String, val segmentsMs: List<Long>, val atMs: Long)

    private val current = ConcurrentHashMap<String, Entry>()

    fun post(app: String, entry: Entry) { current[app] = entry }

    fun clear() = current.clear()

    /** Formato curto do /watch.json: id, k (tipo), t (título), b (texto), v (liga, pausa, … ms), age (min). */
    fun forApp(app: String, nowMs: Long = System.currentTimeMillis()): Map<String, Any?>? {
        val e = current[app] ?: return null
        if (nowMs - e.atMs > TTL_MS) { current.remove(app, e); return null }
        return linkedMapOf("id" to e.id, "k" to e.kind, "t" to e.title, "b" to e.body, "v" to e.segmentsMs,
            "age" to ((nowMs - e.atMs).coerceAtLeast(0L) / 60_000L).toInt())
    }

    companion object { const val TTL_MS = 15 * 60_000L }
}
