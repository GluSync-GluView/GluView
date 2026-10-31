package com.gluview.app.watchface

/**
 * Modelos de visor "prontos para uso" (ready-to-go), inspirados no fluxo do WatchDrip/xDrip,
 * onde o usuario parte de um layout pronto e ajusta. Cada preset posiciona os componentes em
 * fracoes do tamanho do wearable, entao funciona em qualquer formato/resolucao.
 *
 * Premissa: o usuario PODE construir do zero, mas ja comeca com algo bonito e funcional.
 */
data class WatchFacePreset(
    val id: String,
    val name: String,
    val description: String,
    val build: (WearableSpec) -> List<WatchFaceComponent>
)

object WatchFacePresets {

    // Paleta consistente
    private const val WHITE = 0xFFFFFFFFL
    private const val GREEN = 0xFF69F0AEL
    private const val CYAN = 0xFF4DD0E1L
    private const val GREY = 0xFFB0BEC5L
    private const val AMBER = 0xFFFFB74DL

    val all: List<WatchFacePreset> = listOf(
        WatchFacePreset(
            "watchdrip_classic",
            "Classico (WatchDrip)",
            "O visual original que ja funciona na sua Mi Band: grafico no topo, glicemia grande e variacao embaixo."
        ) { w ->
            listOf(
                place(w, GlucoseChartKind, variation = 1, fy = 0.01f, fw = 1.0f, fh = 0.56f, z = 0, color = CYAN, font = "Default", style = "minimal"),
                place(w, GlucoseCurrentKind, variation = 1, fy = 0.60f, fw = 0.92f, fh = 0.23f, z = 1, color = WHITE, font = "Sans", style = "bold", align = WatchFaceTextAlign.END),
                place(w, GlucoseDeltaKind, variation = 1, fy = 0.83f, fw = 0.92f, fh = 0.09f, z = 2, color = GREY, font = "Default", style = "classic", align = WatchFaceTextAlign.END)
            )
        },
        WatchFacePreset(
            "glucose_focus",
            "Glicemia em destaque",
            "Glicemia grande ao centro, hora no topo e mini-grafico embaixo."
        ) { w ->
            listOf(
                place(w, TimeHmKind, variation = 2, fy = 0.09f, fw = 0.86f, fh = 0.15f, z = 0, color = WHITE, font = "Sans", style = "bold"),
                place(w, GlucoseCurrentKind, variation = 2, fy = 0.33f, fw = 0.92f, fh = 0.26f, z = 1, color = GREEN, font = "Sans", style = "bold"),
                place(w, DateDmyKind, variation = 8, fy = 0.62f, fw = 0.78f, fh = 0.08f, z = 2, color = GREY, font = "Sans", style = "minimal"),
                place(w, GlucoseChartKind, variation = 8, fy = 0.73f, fw = 0.92f, fh = 0.20f, z = 3, color = CYAN, font = "Default", style = "minimal")
            )
        },
        WatchFacePreset(
            "complete",
            "Completo",
            "Dia da semana, hora, glicemia, grafico e vida do sensor."
        ) { w ->
            listOf(
                place(w, WeekdayKind, variation = 6, fy = 0.05f, fw = 0.7f, fh = 0.07f, z = 0, color = GREY, font = "Sans", style = "minimal"),
                place(w, TimeHmKind, variation = 2, fy = 0.13f, fw = 0.82f, fh = 0.13f, z = 1, color = WHITE, font = "Sans", style = "bold"),
                place(w, GlucoseCurrentKind, variation = 2, fy = 0.28f, fw = 0.9f, fh = 0.20f, z = 2, color = GREEN, font = "Sans", style = "bold"),
                place(w, DateDmKind, variation = 8, fy = 0.50f, fw = 0.62f, fh = 0.07f, z = 3, color = GREY, font = "Sans", style = "minimal"),
                place(w, GlucoseChartKind, variation = 8, fy = 0.59f, fw = 0.92f, fh = 0.19f, z = 4, color = CYAN, font = "Default", style = "minimal"),
                place(w, SensorLifeKind, variation = 5, fy = 0.83f, fw = 0.74f, fh = 0.08f, z = 5, color = AMBER, font = "Sans", style = "pill")
            )
        },
        WatchFacePreset(
            "minimal",
            "Minimalista",
            "So o essencial: hora e glicemia, bem grandes."
        ) { w ->
            listOf(
                place(w, TimeHmKind, variation = 4, fy = 0.22f, fw = 0.9f, fh = 0.20f, z = 0, color = WHITE, font = "Sans", style = "bold"),
                place(w, GlucoseCurrentKind, variation = 2, fy = 0.50f, fw = 0.9f, fh = 0.24f, z = 1, color = GREEN, font = "Sans", style = "bold")
            )
        },
        WatchFacePreset(
            "daily_complete",
            "Visor completo (dia a dia)",
            "Dia, hora, data, grafico, glicemia, variacao e status do sensor — todos os dados, com espaco pra ser legivel no dia a dia."
        ) { w ->
            listOf(
                place(w, WeekdayKind, variation = 1, fy = 0.00f, fw = 0.9f, fh = 0.055f, z = 0, color = GREY, font = "Sans", style = "minimal"),
                place(w, TimeHmKind, variation = 1, fy = 0.065f, fw = 0.86f, fh = 0.13f, z = 1, color = WHITE, font = "Sans", style = "bold"),
                place(w, DateDmyKind, variation = 1, fy = 0.205f, fw = 0.8f, fh = 0.055f, z = 2, color = GREY, font = "Sans", style = "minimal"),
                place(w, GlucoseChartKind, variation = 1, fy = 0.27f, fw = 1.0f, fh = 0.32f, z = 3, color = CYAN, font = "Default", style = "minimal"),
                place(w, GlucoseCurrentKind, variation = 1, fy = 0.60f, fw = 0.92f, fh = 0.17f, z = 4, color = GREEN, font = "Sans", style = "bold", align = WatchFaceTextAlign.END),
                place(w, GlucoseDeltaKind, variation = 1, fy = 0.775f, fw = 0.92f, fh = 0.065f, z = 5, color = GREY, font = "Default", style = "classic", align = WatchFaceTextAlign.END),
                place(w, SensorConnectedKind, variation = 1, fy = 0.85f, fw = 0.7f, fh = 0.065f, z = 6, color = CYAN, font = "Sans", style = "pill"),
                place(w, SensorLifeKind, variation = 1, fy = 0.92f, fw = 0.7f, fh = 0.065f, z = 7, color = AMBER, font = "Sans", style = "minimal")
            )
        },
        WatchFacePreset(
            "debug_all_types",
            "Debug (todos os tipos)",
            "Empilha praticamente todos os tipos de componente numa tela so, pra validar a geracao/envio na banda real de uma vez."
        ) { w ->
            listOf(
                place(w, WeekdayKind, variation = 1, fy = 0.01f, fw = 0.94f, fh = 0.07f, z = 0, color = GREY, font = "Sans", style = "minimal"),
                place(w, TimeHmsKind, variation = 1, fy = 0.09f, fw = 0.94f, fh = 0.11f, z = 1, color = WHITE, font = "Sans", style = "compact"),
                place(w, DateDmyKind, variation = 1, fy = 0.21f, fw = 0.94f, fh = 0.07f, z = 2, color = GREY, font = "Sans", style = "minimal"),
                place(w, GlucoseCurrentKind, variation = 1, fy = 0.29f, fw = 0.94f, fh = 0.15f, z = 3, color = GREEN, font = "Sans", style = "bold"),
                place(w, GlucoseDeltaKind, variation = 1, fy = 0.45f, fw = 0.94f, fh = 0.07f, z = 4, color = GREY, font = "Default", style = "classic"),
                place(w, SensorConnectedKind, variation = 1, fy = 0.53f, fw = 0.94f, fh = 0.08f, z = 5, color = CYAN, font = "Sans", style = "pill"),
                place(w, SensorLifeKind, variation = 1, fy = 0.62f, fw = 0.94f, fh = 0.07f, z = 6, color = AMBER, font = "Sans", style = "minimal"),
                place(w, GlucoseChartKind, variation = 1, fy = 0.70f, fw = 1.0f, fh = 0.29f, z = 7, color = CYAN, font = "Default", style = "minimal")
            )
        }
    )

    /** Cria um componente posicionado por fracoes do wearable, centralizado horizontalmente. */
    private fun place(
        w: WearableSpec,
        kind: ComponentKind,
        variation: Int,
        fy: Float,
        fw: Float,
        fh: Float,
        z: Int,
        color: Long,
        font: String,
        style: String,
        align: WatchFaceTextAlign = WatchFaceTextAlign.CENTER
    ): WatchFaceComponent {
        val width = (w.widthPx * fw).coerceAtLeast(20f)
        val height = (w.heightPx * fh).coerceAtLeast(16f)
        val x = ((w.widthPx - width) / 2f).coerceAtLeast(0f)
        val y = (w.heightPx * fy).coerceAtLeast(0f)
        return WatchFaceComponent(
            typeId = kind.id,
            variation = variation,
            label = "${kind.label} V$variation",
            x = x,
            y = y,
            width = width,
            height = height,
            color = color,
            font = font,
            alignment = align.value,
            zIndex = z,
            styleTag = style
        )
    }
}
