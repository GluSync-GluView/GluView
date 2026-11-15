package com.gluview.wear.tile

import android.graphics.Bitmap
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.gluview.wear.MainActivity
import com.gluview.wear.R
import com.gluview.wear.contract.SnapshotSource
import com.gluview.wear.contract.WatchFaceSnapshot
import com.gluview.wear.data.SnapshotStore
import com.gluview.wear.ui.Display
import com.google.common.util.concurrent.ListenableFuture
import java.nio.ByteBuffer

/**
 * Tile do GluView (Fase 6): valor grande na cor da zona, seta e delta, hora DA MEDIÇÃO, minigráfico
 * e de onde veio ("via celular"/"via nuvem"). Leitura velha → "--" cinza e sem gráfico.
 * Frescor de 5 min: o sistema pede o tile de novo e a regra de idade é reavaliada sem leitura nova.
 */
class GlucoseTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture {
            val now = System.currentTimeMillis()
            val snapshot = SnapshotStore.load(this@GlucoseTileService)
            val hasGraph = snapshot != null && !snapshot.isStale(now) && SnapshotStore.graphPng(this@GlucoseTileService) != null
            TileBuilders.Tile.Builder()
                .setResourcesVersion(resourcesVersion(snapshot, hasGraph))
                .setFreshnessIntervalMillis(FRESHNESS_MS)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(snapshot, now, hasGraph)))
                .build()
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture {
            val builder = ResourceBuilders.Resources.Builder().setVersion(requestParams.version)
            SnapshotStore.graphBitmap(this@GlucoseTileService)?.let { bmp ->
                builder.addIdToImageMapping(GRAPH_ID, rgb565Image(bmp))
            }
            builder.build()
        }

    private fun layout(snapshot: WatchFaceSnapshot?, now: Long, hasGraph: Boolean): LayoutElementBuilders.LayoutElement {
        val color = Display.zoneColor(snapshot, now)
        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        if (snapshot == null) {
            column.addContent(text(getString(R.string.no_data), 14f, Display.COLOR_TEXT_DIM))
        } else {
            val stale = snapshot.isStale(now)
            column.addContent(text(snapshot.displayValue(now), 44f, color, bold = true))
            val trend = listOf(snapshot.arrow(now), snapshot.deltaText(now)).filter { it.isNotEmpty() }.joinToString("  ")
            column.addContent(text(if (stale) getString(R.string.stale) else trend.ifEmpty { Display.unitLabel(snapshot) }, 16f, if (stale) Display.COLOR_STALE else 0xFFFFFFFF.toInt()))
            if (hasGraph) {
                column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
                column.addContent(
                    LayoutElementBuilders.Image.Builder().setResourceId(GRAPH_ID)
                        .setWidth(dp(140f)).setHeight(dp(56f)).build()
                )
            }
            val source = getString(if (snapshot.source == SnapshotSource.CLOUD) R.string.via_cloud else R.string.via_phone)
            column.addContent(text("${getString(R.string.measured_at, Display.measuredTime(snapshot))} · $source", 12f, Display.COLOR_TEXT_DIM))
        }

        // Plano 17, Fase 4 — próximo lembrete e atalhos de registro no diário.
        val day = com.gluview.wear.data.DiaryStore.loadDay(this)?.takeIf { com.gluview.wear.contract.WearDayInfo.isFromToday(it, now) }
        day?.takeIf { it.nextReminderAtMs > now }?.let { d ->
            val t = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(d.nextReminderAtMs))
            // Largura fixa e uma linha só: na tela redonda o texto comprido encostava na borda.
            column.addContent(
                LayoutElementBuilders.Box.Builder().setWidth(dp(170f))
                    .addContent(
                        LayoutElementBuilders.Text.Builder().setText(getString(R.string.next_reminder_at, t, d.nextReminderTitle))
                            .setMaxLines(1).setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(11f))
                                .setColor(argb(Display.COLOR_TEXT_DIM)).build())
                            .build()
                    ).build()
            )
        }
        column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
        column.addContent(
            LayoutElementBuilders.Row.Builder()
                .addContent(shortcut("log_meal", getString(R.string.tile_add_carbs), "meal"))
                .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build())
                .addContent(shortcut("log_insulin", getString(R.string.tile_add_insulin), "insulin"))
                .build()
        )

        return LayoutElementBuilders.Box.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
            .setHeight(androidx.wear.protolayout.DimensionBuilders.expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder().setClickable(
                    ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(
                        ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                            ActionBuilders.AndroidActivity.Builder()
                                .setPackageName(packageName)
                                .setClassName(MainActivity::class.java.name).build()
                        ).build()
                    ).build()
                ).build()
            )
            .addContent(column.build())
            .build()
    }

    /** Botão do bloco que abre o registro do diário já no tipo certo. */
    private fun shortcut(id: String, label: String, type: String): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(dp(64f)).setHeight(dp(32f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder().setColor(argb(0xFF1E5AA8.toInt()))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(16f)).build()).build()
                    )
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder().setId(id).setOnClick(
                            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                                ActionBuilders.AndroidActivity.Builder()
                                    .setPackageName(packageName)
                                    .setClassName(com.gluview.wear.LogActivity::class.java.name)
                                    .addKeyToExtraMapping(com.gluview.wear.LogActivity.EXTRA_TYPE,
                                        ActionBuilders.AndroidStringExtra.Builder().setValue(type).build())
                                    .build()
                            ).build()
                        ).build()
                    ).build()
            )
            .addContent(text(label, 12f, 0xFFFFFFFF.toInt(), bold = true))
            .build()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) =
        LayoutElementBuilders.Text.Builder().setText(s).setMaxLines(2)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build()
            ).build()

    companion object {
        private const val GRAPH_ID = "graph"
        private const val FRESHNESS_MS = 5 * 60_000L
        private const val IMG_W = 200
        private const val IMG_H = 80

        /** Versão muda quando muda a leitura ou some o gráfico → o sistema pede a imagem nova. */
        private fun resourcesVersion(s: WatchFaceSnapshot?, hasGraph: Boolean) =
            "${s?.measuredAtMs ?: 0}-${if (hasGraph) 1 else 0}"

        /** PNG → RGB_565 cru (formato que o ProtoLayout aceita sem decodificar), reduzido para 200×80. */
        private fun rgb565Image(src: Bitmap): ResourceBuilders.ImageResource {
            val scaled = Bitmap.createScaledBitmap(src, IMG_W, IMG_H, true).copy(Bitmap.Config.RGB_565, false)
            val buffer = ByteBuffer.allocate(scaled.byteCount)
            scaled.copyPixelsToBuffer(buffer)
            return ResourceBuilders.ImageResource.Builder().setInlineResource(
                ResourceBuilders.InlineImageResource.Builder()
                    .setData(buffer.array()).setWidthPx(IMG_W).setHeightPx(IMG_H)
                    .setFormat(ResourceBuilders.IMAGE_FORMAT_RGB_565).build()
            ).build()
        }
    }
}
