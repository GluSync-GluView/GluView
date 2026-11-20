package com.gluview.wearables.core

import java.util.ArrayDeque

/**
 * Fila de comandos por prioridade com coalescência: vários [WearableCommand.SendGlucose] seguidos
 * viram um só (o mais recente), para não saturar a banda/relógio.
 */
class WearableCommandQueue {
    private val high = ArrayDeque<WearableCommand>()   // vibração, soneca
    private val normal = ArrayDeque<WearableCommand>() // ping/bateria
    @Volatile private var latestGlucose: WearableCommand.SendGlucose? = null

    @Synchronized
    fun enqueue(cmd: WearableCommand) {
        when (cmd) {
            is WearableCommand.SendGlucose -> latestGlucose = cmd
            is WearableCommand.Vibrate, is WearableCommand.SnoozeAlerts -> high.addLast(cmd)
            else -> normal.addLast(cmd)
        }
    }

    @Synchronized
    fun poll(): WearableCommand? {
        if (high.isNotEmpty()) return high.removeFirst()
        val g = latestGlucose
        if (g != null) { latestGlucose = null; return g }
        if (normal.isNotEmpty()) return normal.removeFirst()
        return null
    }

    @Synchronized
    fun drainAll(): List<WearableCommand> {
        val out = mutableListOf<WearableCommand>()
        while (high.isNotEmpty()) out += high.removeFirst()
        latestGlucose?.let { out += it; latestGlucose = null }
        while (normal.isNotEmpty()) out += normal.removeFirst()
        return out
    }

    @Synchronized
    fun isEmpty(): Boolean = high.isEmpty() && latestGlucose == null && normal.isEmpty()

    @Synchronized
    fun size(): Int = high.size + normal.size + if (latestGlucose != null) 1 else 0
}
