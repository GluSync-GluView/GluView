package com.gluview.app.ui.screen.watchface

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gluview.app.R
import com.gluview.app.data.sibionics.SibionicsSensorStatus
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.components.kit.accent
import com.gluview.app.util.GlucoseGraphRenderer
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.GraphReading
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.graph.GraphLayout
import com.gluview.app.util.graph.GraphSpec
import com.gluview.app.util.graph.GraphVariant
import com.gluview.app.util.graph.GraphWindow
import com.gluview.app.watchface.ChartComponentKind
import com.gluview.app.watchface.ComponentRegistry
import com.gluview.app.watchface.StatusComponentKind
import com.gluview.app.watchface.TextComponentKind
import com.gluview.app.watchface.WatchFaceComponent
import com.gluview.app.watchface.WatchFaceComponentDefinition
import com.gluview.app.watchface.WatchFaceTextAlign
import com.gluview.app.watchface.WearableKind
import com.gluview.app.watchface.WearableShape
import com.gluview.app.watchface.WearableSpec
import kotlin.math.roundToInt

@Immutable
data class WatchFaceRuntimeData(
    val nowMs: Long,
    val reading: GlucoseReading?,
    val graphReadings: List<GraphReading>,
    val sensorStatus: SibionicsSensorStatus,
    // Limiares configurados em Ajustes > Alertas ("Baixo"/"Alto") — default 70/180 preserva o
    // comportamento antigo pra quem constrói o runtime sem repassar o valor real.
    val hipo: Float = 70f,
    val hiper: Float = 180f,
    /** Limites urgentes de Ajustes > Alertas (L2). */
    val urgentLow: Float = 55f,
    val urgentHigh: Float = 250f,
    val timeFormat: TimeFormat = TimeFormat.H24,
    /** 0..100. Usado pelo componente PhoneBatteryKind (bateria do celular). */
    val batteryPercent: Int = 0,
    /** Usado pelo GlucoseUnitKind (rótulo "mg/dL"/"mmol/L"). Não afeta GlucoseCurrentKind/
     *  GlucoseDeltaKind, que continuam sempre em mg/dL — mudar isso seria alterar comportamento
     *  já em produção, fora do escopo desta adição. */
    val unit: GlucoseUnit = GlucoseUnit.MG_DL
)

data class WatchFaceCanvasOptions(
    val showGrid: Boolean = false,
    val showGuides: Boolean = false,
    val snapToGrid: Boolean = false,
    val editable: Boolean = false
)

@Composable
fun WatchFacePreview(
    wearable: WearableSpec,
    components: List<WatchFaceComponent>,
    runtime: WatchFaceRuntimeData,
    modifier: Modifier = Modifier,
    options: WatchFaceCanvasOptions = WatchFaceCanvasOptions(),
    selectedId: String? = null,
    overlapIds: Set<String> = emptySet(),
    onSelect: (String) -> Unit = {},
    onTransformStart: () -> Unit = {},
    onTransformEnd: () -> Unit = {},
    onMove: (String, Float, Float) -> Unit = { _, _, _ -> },
    onResize: (String, Float, Float) -> Unit = { _, _, _ -> },
    onDelete: (String) -> Unit = {},
    /** Prévia não editável: o TalkBack lê esta frase em vez dos textos crus ("12:34", "120 →"). */
    contentDescription: String? = null
) {
    // A prévia representa PIXELS do wearable, não texto do app: sem fontScale (com fonte 1,3×/2×
    // o texto passava da caixa e virava "12:3…"; a pulseira desenha em px e não muda).
    val density = LocalDensity.current
    val semanticsModifier = if (!options.editable && contentDescription != null)
        Modifier.clearAndSetSemantics { this.contentDescription = contentDescription } else Modifier
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
    BoxWithCanvasScale(wearable = wearable, modifier = modifier.then(semanticsModifier)) { canvasScale ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shapeFor(wearable.shape))
                .background(Color.Black)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                if (options.showGrid) {
                    val gridPx = 10f * canvasScale
                    var x = 0f
                    while (x <= size.width) {
                        drawLine(Color(0x22FFFFFF), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                        x += gridPx
                    }
                    var y = 0f
                    while (y <= size.height) {
                        drawLine(Color(0x22FFFFFF), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                        y += gridPx
                    }
                }
                if (options.showGuides) {
                    drawLine(Color(0x6642A5F5), Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), strokeWidth = 1.5f)
                    drawLine(Color(0x6642A5F5), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = 1.5f)
                }
            }

            components.sortedBy { it.zIndex }.forEach { component ->
                RenderWatchFaceComponent(
                    component = component,
                    runtime = runtime,
                    canvasScale = canvasScale,
                    boundsW = wearable.widthPx.toFloat(),
                    boundsH = wearable.heightPx.toFloat(),
                    selected = component.id == selectedId,
                    overlapping = component.id in overlapIds,
                    editable = options.editable,
                    onSelect = onSelect,
                    onTransformStart = onTransformStart,
                    onTransformEnd = onTransformEnd,
                    onMove = onMove,
                    onResize = onResize,
                    onDelete = onDelete
                )
            }
        }
    }
    }
}

@Composable
private fun BoxWithCanvasScale(
    wearable: WearableSpec,
    modifier: Modifier,
    content: @Composable (Float) -> Unit
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val scale = minOf(
            maxWidthPx / wearable.widthPx.toFloat(),
            maxHeightPx / wearable.heightPx.toFloat()
        ).coerceAtLeast(0.01f)
        Box(
            modifier = Modifier
                .size(
                    with(density) { (wearable.widthPx * scale).toDp() },
                    with(density) { (wearable.heightPx * scale).toDp() }
                )
                .align(Alignment.Center)
        ) {
            content(scale)
        }
    }
}

@Composable
private fun RenderWatchFaceComponent(
    component: WatchFaceComponent,
    runtime: WatchFaceRuntimeData,
    canvasScale: Float,
    boundsW: Float,
    boundsH: Float,
    selected: Boolean,
    overlapping: Boolean,
    editable: Boolean,
    onSelect: (String) -> Unit,
    onTransformStart: () -> Unit,
    onTransformEnd: () -> Unit,
    onMove: (String, Float, Float) -> Unit,   // x, y ABSOLUTOS (px do wearable), no fim do gesto
    onResize: (String, Float, Float) -> Unit, // largura, altura ABSOLUTAS, no fim do gesto
    onDelete: (String) -> Unit
) {
    val density = LocalDensity.current
    val borderColor = when {
        overlapping -> Tone.Warning.accent()
        selected -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }

    // Arraste/redimensionamento suaves: posição e tamanho vêm de estado LOCAL durante o gesto,
    // sem recompor a tela inteira a cada frame. O ViewModel só é atualizado no FIM do gesto.
    // Fora do gesto, o estado local segue o modelo (undo/redo, sliders, carregamento).
    var dragging by remember { mutableStateOf(false) }
    var resizing by remember { mutableStateOf(false) }
    var pos by remember(component.id) { mutableStateOf(Offset(component.x, component.y)) }
    var dim by remember(component.id) { mutableStateOf(Offset(component.width, component.height)) }
    LaunchedEffect(component.x, component.y) { if (!dragging) pos = Offset(component.x, component.y) }
    LaunchedEffect(component.width, component.height) { if (!resizing) dim = Offset(component.width, component.height) }

    val rendered = if (resizing) component.copy(width = dim.x, height = dim.y) else component

    val dragModifier = if (editable) {
        Modifier.pointerInput(component.id) {
            detectDragGestures(
                onDragStart = {
                    pos = Offset(component.x, component.y)
                    dragging = true
                    onTransformStart()
                    onSelect(component.id)
                },
                onDragEnd = {
                    dragging = false
                    onMove(component.id, pos.x, pos.y)
                    onTransformEnd()
                },
                onDragCancel = {
                    dragging = false
                    onMove(component.id, pos.x, pos.y)
                    onTransformEnd()
                },
                onDrag = { change, drag ->
                    change.consume()
                    val nx = (pos.x + drag.x / canvasScale).coerceIn(0f, (boundsW - dim.x).coerceAtLeast(0f))
                    val ny = (pos.y + drag.y / canvasScale).coerceIn(0f, (boundsH - dim.y).coerceAtLeast(0f))
                    pos = Offset(nx, ny)
                }
            )
        }
    } else Modifier

    // Toque seleciona o componente (sem precisar arrastar) — corrige a confusao de "como seleciono?".
    val tapModifier = if (editable) {
        Modifier.pointerInput(component.id) {
            detectTapGestures(onTap = { onSelect(component.id) })
        }
    } else Modifier

    Box(
        modifier = Modifier
            .offset { IntOffset((pos.x * canvasScale).roundToInt(), (pos.y * canvasScale).roundToInt()) }
            .size(
                with(density) { (dim.x * canvasScale).toDp() },
                with(density) { (dim.y * canvasScale).toDp() }
            )
            .graphicsLayer {
                scaleX = component.scale
                scaleY = component.scale
                rotationZ = component.rotation
                alpha = component.alpha.coerceIn(0f, 1f)
            }
            .then(tapModifier)
            .then(dragModifier)
            .border(if (borderColor == Color.Transparent) 0.dp else 1.dp, borderColor, RoundedCornerShape(4.dp))
    ) {
        ComponentContent(rendered, runtime, canvasScale)

        if (editable && selected) {
            val name = componentDisplayName(component)
            // Alças: 24dp de desenho, 44dp de toque, metade para fora do canto (antes 24/22dp de
            // toque por cima do conteúdo, cobrindo quase todo um componente pequeno).
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset((-12).dp, (-12).dp)
                    .size(44.dp)
                    .clickable { onDelete(component.id) },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.error, RoundedCornerShape(percent = 50)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.wf_remove_named, name),
                        tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(15.dp))
                }
            }
            val resizeDescription = stringResource(R.string.wf_resize_named, name)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(12.dp, 12.dp)
                    .size(44.dp)
                    .semantics { this.contentDescription = resizeDescription }
                    .pointerInput(component.id) {
                        detectDragGestures(
                            onDragStart = {
                                dim = Offset(component.width, component.height)
                                resizing = true
                                onTransformStart()
                            },
                            onDragEnd = {
                                resizing = false
                                onResize(component.id, dim.x, dim.y)
                                onTransformEnd()
                            },
                            onDragCancel = {
                                resizing = false
                                onResize(component.id, dim.x, dim.y)
                                onTransformEnd()
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                val nw = (dim.x + drag.x / canvasScale).coerceIn(20f, boundsW)
                                val nh = (dim.y + drag.y / canvasScale).coerceIn(16f, boundsH)
                                dim = Offset(nw, nh)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)))
            }
        }
    }
}

@Composable
private fun ComponentContent(component: WatchFaceComponent, runtime: WatchFaceRuntimeData, canvasScale: Float) {
    when (val kind = ComponentRegistry.byId(component.typeId)) {
        is ChartComponentKind -> GlucoseWatchChart(component, runtime)
        is StatusComponentKind -> SensorConnected(component, kind, runtime, canvasScale)
        is TextComponentKind -> TextComponent(component, kind.textFor(component, runtime), canvasScale)
    }
}

@Composable
private fun TextComponent(component: WatchFaceComponent, text: String, canvasScale: Float) {
    val align = WatchFaceTextAlign.fromValue(component.alignment)
    val boxAlignment = when (align) {
        WatchFaceTextAlign.START -> Alignment.CenterStart
        WatchFaceTextAlign.CENTER -> Alignment.Center
        WatchFaceTextAlign.END -> Alignment.CenterEnd
    }
    val shown = decoratedText(component, text)
    val heightFactor = when (component.styleTag) {
        "compact" -> 0.40f
        "stacked" -> 0.40f
        "bold" -> 0.62f
        "accent" -> 0.58f
        else -> 0.50f
    }
    // Fonte limitada por ALTURA e por LARGURA, para o texto nunca ser cortado — essencial em
    // bandas estreitas (Mi Band). Convertemos px do wearable -> px renderizados (× canvasScale)
    // -> sp (÷ densidade), senao a fonte (sp) nao acompanha o zoom do preview. ~0.60 da largura
    // do glifo por caractere.
    val density = LocalDensity.current.density
    val longestLine = (shown.lines().maxOfOrNull { it.length } ?: 1).coerceAtLeast(1)
    val heightSp = component.height * heightFactor * canvasScale / density
    val widthSp = (component.width * canvasScale) / (longestLine * 0.60f * density)
    val fontSize = minOf(heightSp, widthSp).coerceIn(7f, 200f).sp
    val color = Color(component.color)
    val family = when (component.font) {
        "Serif" -> FontFamily.Serif
        "Mono" -> FontFamily.Monospace
        "Condensed" -> FontFamily.SansSerif
        else -> FontFamily.Default
    }
    val background = when (component.styleTag) {
        "pill" -> color.copy(alpha = 0.16f)
        "accent" -> Color.White.copy(alpha = 0.08f)
        "outline" -> Color.Transparent
        else -> Color.Transparent
    }
    val border = if (component.styleTag == "outline") color.copy(alpha = 0.7f) else Color.Transparent

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background, RoundedCornerShape(999.dp))
            .border(if (border == Color.Transparent) 0.dp else 1.dp, border, RoundedCornerShape(999.dp)),
        contentAlignment = boxAlignment
    ) {
        Text(
            text = shown,
            color = color,
            fontSize = fontSize,
            fontFamily = family,
            fontWeight = if (component.styleTag in listOf("bold", "accent")) FontWeight.Bold else FontWeight.Normal,
            textAlign = when (align) {
                WatchFaceTextAlign.START -> TextAlign.Start
                WatchFaceTextAlign.CENTER -> TextAlign.Center
                WatchFaceTextAlign.END -> TextAlign.End
            },
            maxLines = if (component.styleTag == "stacked") 2 else 1,
            // Sem "…": a pulseira desenha o texto inteiro (drawText não corta), a prévia também.
            softWrap = component.styleTag == "stacked",
            overflow = TextOverflow.Visible,
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun decoratedText(component: WatchFaceComponent, text: String): String = when (component.styleTag) {
    "labelled" -> "${component.label.substringBefore(" ")}\n$text"
    "split" -> text.replace(":", " : ").replace("/", " / ")
    else -> text
}

@Composable
private fun SensorConnected(component: WatchFaceComponent, kind: StatusComponentKind, runtime: WatchFaceRuntimeData, canvasScale: Float) {
    // Estilo do próprio componente, como a pulseira desenha (antes a prévia forçava "pill" e
    // divergia do pulso — decisão 5.6 #2 do redesign: a prévia segue a pulseira).
    TextComponent(
        component.copy(color = kind.statusColor(component, runtime)),
        kind.statusText(runtime),
        canvasScale
    )
}

/**
 * Gráfico do visor com o MESMO desenho da pulseira (redesign visual, Fase 9): o layout da variante
 * WATCH do renderer (fundo preto, faixa < 55, hipo/hiper tracejadas com rótulo, 100/150 neutras,
 * linha cinza + pontos por zona, janela fixa de 3 h), calculado no tamanho do componente em px do
 * wearable e escalado para a prévia. Antes era um Canvas próprio, na cor do componente e com fundo
 * branco a ~51% por um literal de 9 dígitos (0x181FFFFFF). Desenha direto no nativeCanvas, como o
 * gráfico da Home (sem bitmap; caminho validado no Moto Z2 no spike de 03/10).
 */
@Composable
private fun GlucoseWatchChart(component: WatchFaceComponent, runtime: WatchFaceRuntimeData) {
    val w = component.width.roundToInt().coerceAtLeast(1)
    val h = component.height.roundToInt().coerceAtLeast(1)
    // Recalcula no máximo 1×/min (o runtime anda a cada segundo).
    val minute = runtime.nowMs / 60_000L
    val layout = remember(runtime.graphReadings, runtime.hipo, runtime.hiper, runtime.urgentLow, runtime.urgentHigh, w, h, minute) {
        GraphLayout.compute(
            runtime.graphReadings,
            GraphSpec(w, h, GraphWindow.Trailing(3), nowMs = runtime.nowMs, variant = GraphVariant.WATCH,
                hipo = runtime.hipo, hiper = runtime.hiper, urgentLow = runtime.urgentLow, urgentHigh = runtime.urgentHigh),
            GlucoseGraphRenderer.measureText
        )
    }
    Canvas(Modifier.fillMaxSize()) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.scale(size.width / w, size.height / h)
            // O fundo do gráfico (DrawOp.Fill) é um drawColor, que pinta TODA a área de recorte — e
            // este Canvas não recorta no tamanho do componente. Sem o clip, o preto cobria os
            // componentes desenhados antes do gráfico (hora, dia, data sumiam nos modelos Completo,
            // Glicemia em destaque e Visor completo, em qualquer aparelho).
            native.clipRect(0f, 0f, w.toFloat(), h.toFloat())
            GlucoseGraphRenderer.draw(native, layout)
            native.restore()
        }
    }
}

// textFor()/deltaText() foram absorvidos por ComponentKind.textFor() (ver ComponentKind.kt) —
// eliminava duplicação exata com WatchFaceDesignGenerator.kt (achado P3/P5 do plano
// 06_watchfaces_v2_arquitetura.md: as duas cópias eram "espelhadas" byte a byte de propósito;
// agora são uma implementação só, usada pelos dois renderers).

/**
 * Miniatura de uma variação de componente para a biblioteca do editor. Renderiza o componente
 * com os valores padrão da definição e dados REAIS ([runtime]), para o usuário VER cada variação
 * em vez de só ler "V1, V2, V3...". Reusa [WatchFacePreview] num wearable sintético do tamanho
 * do componente.
 */
@Composable
fun WatchFaceComponentThumbnail(
    definition: WatchFaceComponentDefinition,
    runtime: WatchFaceRuntimeData,
    modifier: Modifier = Modifier
) {
    val wearable = remember(definition.key) {
        WearableSpec(
            id = "thumb_${definition.key}",
            brand = "",
            name = "",
            widthPx = definition.defaultWidth.roundToInt().coerceAtLeast(1),
            heightPx = definition.defaultHeight.roundToInt().coerceAtLeast(1),
            kind = WearableKind.SMARTWATCH,
            shape = WearableShape.RECTANGLE
        )
    }
    val component = remember(definition.key) {
        WatchFaceComponent(
            typeId = definition.kind.id,
            variation = definition.variation,
            label = definition.name,
            x = 0f,
            y = 0f,
            width = definition.defaultWidth,
            height = definition.defaultHeight,
            color = definition.defaultColor,
            font = definition.defaultFont,
            alignment = definition.defaultAlignment.value,
            styleTag = definition.styleTag
        )
    }
    WatchFacePreview(
        wearable = wearable,
        components = listOf(component),
        runtime = runtime,
        modifier = modifier
    )
}

private fun shapeFor(shape: WearableShape) = when (shape) {
    WearableShape.ROUND -> RoundedCornerShape(999.dp)
    WearableShape.SQUIRCLE -> RoundedCornerShape(34.dp)
    WearableShape.RECTANGLE -> RoundedCornerShape(12.dp)
}

fun overlappingComponentIds(components: List<WatchFaceComponent>): Set<String> {
    val ids = mutableSetOf<String>()
    for (i in components.indices) {
        for (j in i + 1 until components.size) {
            val a = components[i]
            val b = components[j]
            val overlaps = a.x < b.right() && a.right() > b.x && a.y < b.bottom() && a.bottom() > b.y
            if (overlaps) {
                ids += a.id
                ids += b.id
            }
        }
    }
    return ids
}
