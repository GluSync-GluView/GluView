package com.gluview.app.overlay

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.gluview.app.MainActivity
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.withAppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Plano 21, F1 — janela flutuante de glicemia sobre os outros apps (referência de comportamento:
 * Juggluco `Floating.java`; implementação própria com Views, sem Compose, porque a janela vive fora
 * de Activity). Quem alimenta é o GluSyncService, no mesmo ponto que atualiza a notificação
 * persistente (cada leitura nova + tique de 1 min), então segue também a pessoa acompanhada no modo
 * seguidor. Um tique próprio de 30 s refaz a idade e derruba para "Sem sinal" se o serviço parar
 * de mandar leitura — leitura velha nunca fica parecendo atual.
 *
 * Tudo que mexe em View/WindowManager roda na main thread ([main]).
 */
@Singleton
class FloatingGlucoseWindow @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val preferences: AppPreferences
) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wm get() = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var config = FloatingConfig()
    private var unit = GlucoseUnit.MG_DL
    private var noSignalMin = 15
    private var language = ""
    private var label: String? = null
    private var reading: GlucoseReading? = null
    private var hiddenUntilMs = 0L
    private var screenLocked = false

    private var root: LinearLayout? = null
    private var labelView: TextView? = null
    private var valueView: TextView? = null
    private var detailView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var receiverRegistered = false

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(appContext)

    /** Ajustes mudaram (ou o serviço subiu). */
    fun applySettings(config: FloatingConfig, unit: GlucoseUnit, noSignalMin: Int, language: String) = main.post {
        // Tamanho, idioma ou posição (o "voltar à posição inicial" dos ajustes) só valem ao recriar
        // a janela. Depois de arrastar, a posição salva já é a atual, então não recria.
        val rebuild = config.scale != this.config.scale || language != this.language ||
            config.x != this.config.x || config.y != this.config.y
        this.config = config; this.unit = unit; this.noSignalMin = noSignalMin; this.language = language
        if (rebuild) removeView()
        refreshVisibility()
    }

    /** Nova leitura (já com a idade aplicada pelo serviço). [personLabel] = nome no modo seguidor. */
    fun update(reading: GlucoseReading?, personLabel: String?) = main.post {
        this.reading = reading
        this.label = personLabel?.takeIf { it.isNotBlank() }
        render()
    }

    /** Serviço parando: some com a janela (ela só existe com o serviço vivo). */
    fun shutdown() = main.post {
        main.removeCallbacks(tick); main.removeCallbacks(unhide)
        removeView()
        unregisterScreenReceiver()
    }

    private val tick = object : Runnable {
        override fun run() { render(); main.postDelayed(this, 30_000L) }
    }
    private val unhide = Runnable { hiddenUntilMs = 0L; refreshVisibility() }

    private fun shouldShow(): Boolean =
        config.enabled && canDrawOverlays() && System.currentTimeMillis() >= hiddenUntilMs &&
            !(config.hideWhenLocked && screenLocked)

    private fun refreshVisibility() {
        if (config.enabled) registerScreenReceiver() else unregisterScreenReceiver()
        if (shouldShow()) {
            if (root == null) addView()
            root?.alpha = config.alpha
            render()
            main.removeCallbacks(tick); main.postDelayed(tick, 30_000L)
        } else {
            main.removeCallbacks(tick)
            removeView()
        }
    }

    private fun ctx(): Context = if (language.isBlank()) appContext else appContext.withAppLanguage(language)

    private fun sp(v: Float) = v * config.scale

    @SuppressLint("ClickableViewAccessibility")
    private fun addView() {
        val c = ctx()
        val dp = c.resources.displayMetrics.density
        val pad = (8 * dp * config.scale).toInt()
        val layout = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad * 3 / 2, pad, pad * 3 / 2, pad)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        fun text(size: Float, bold: Boolean) = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(size))
            setTextColor(Color.WHITE)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        labelView = text(11f, false).also { layout.addView(it) }
        valueView = text(26f, true).also { layout.addView(it) }
        detailView = text(12f, false).also { layout.addView(it) }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val screenW = c.resources.displayMetrics.widthPixels
            x = if (config.x >= 0) config.x else (screenW - 140 * dp * config.scale).toInt().coerceAtLeast(0)
            y = if (config.y >= 0) config.y else (120 * dp).toInt()
        }
        attachGestures(layout, lp)
        runCatching { wm.addView(layout, lp) }.onSuccess {
            root = layout; params = lp
        }.onFailure { labelView = null; valueView = null; detailView = null }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures(view: View, lp: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(appContext).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        val detector = GestureDetector(appContext, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { openApp(); return true }
            override fun onLongPress(e: MotionEvent) { if (!dragging) hideTemporarily() }
        })
        view.setOnTouchListener { _, e ->
            detector.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false }
                MotionEvent.ACTION_MOVE -> if (!config.locked) {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        lp.x = (startX + dx).toInt().coerceAtLeast(0); lp.y = (startY + dy).toInt().coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(view, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    val saved = config.copy(x = lp.x, y = lp.y)
                    config = saved
                    scope.launch { runCatching { preferences.saveFloatingConfig(saved) } }
                }
            }
            true
        }
        // Acessibilidade: o mesmo que o toque (abre o app).
        view.setOnClickListener { openApp() }
    }

    private fun openApp() {
        val i = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { appContext.startActivity(i) }
    }

    private fun hideTemporarily() {
        hiddenUntilMs = System.currentTimeMillis() + config.hideMinutes * 60_000L
        main.removeCallbacks(unhide)
        main.postDelayed(unhide, config.hideMinutes * 60_000L)
        refreshVisibility()
    }

    private fun render() {
        val layout = root ?: return
        val c = ctx()
        val state = FloatingFormat.build(reading, System.currentTimeMillis(), noSignalMin, unit,
            config.showArrow, config.showDelta)
        val noSignal = c.getString(R.string.zone_stale)
        val age = state.ageMinutes?.let { ageText(c, it) }

        labelView?.apply { text = label.orEmpty(); visibility = if (label == null) View.GONE else View.VISIBLE }
        valueView?.text = if (state.value == null) noSignal else "${state.value} ${state.arrow}".trim()
        val detail = buildList {
            if (state.value != null) state.delta?.let { add(it) }
            if (config.showAge || state.stale) age?.let { add(it) }
        }.joinToString(" · ")
        detailView?.apply { text = detail; visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE }

        val dp = c.resources.displayMetrics.density
        val alert = config.highlightAlerts && (state.tone == FloatingTone.URGENT || state.tone == FloatingTone.QUICK_DROP)
        layout.background = GradientDrawable().apply {
            cornerRadius = 16 * dp * config.scale
            setColor(if (config.zoneBackground) toneColor(state.tone) else NEUTRAL)
            if (alert) setStroke((3 * dp).toInt(), Color.WHITE)
        }
        layout.contentDescription = buildString {
            label?.let { append(it).append(". ") }
            if (state.value == null) append(noSignal)
            else append(state.value).append(' ').append(unit.label).append(". ").append(arrowSpoken(c, state.arrow))
            age?.let { append(". ").append(it) }
        }
    }

    private fun ageText(c: Context, minutes: Int): String = when {
        minutes < 1 -> c.getString(R.string.elapsed_now)
        minutes < 60 -> c.resources.getQuantityString(R.plurals.elapsed_minutes, minutes, minutes)
        else -> (minutes / 60).let { c.resources.getQuantityString(R.plurals.elapsed_hours, it, it) }
    }

    private fun arrowSpoken(c: Context, arrow: String): String = when (arrow) {
        "↑↑" -> c.getString(R.string.floating_trend_up_fast)
        "↑", "↗" -> c.getString(R.string.floating_trend_up)
        "→" -> c.getString(R.string.floating_trend_flat)
        "↘", "↓" -> c.getString(R.string.floating_trend_down)
        "↓↓" -> c.getString(R.string.floating_trend_down_fast)
        else -> ""
    }

    private fun removeView() {
        root?.let { v -> runCatching { wm.removeView(v) } }
        root = null; labelView = null; valueView = null; detailView = null; params = null
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenLocked = when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> true
                Intent.ACTION_USER_PRESENT -> false
                Intent.ACTION_SCREEN_ON -> (appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
                else -> screenLocked
            }
            refreshVisibility()
        }
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(appContext, screenReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        screenLocked = (appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    }

    private fun unregisterScreenReceiver() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(screenReceiver) }
        receiverRegistered = false
    }

    private companion object {
        // Mesmas cores do widget simples (WidgetCommon.simpleWidgetBackground), texto branco.
        val NEUTRAL = Color.parseColor("#E6202124")
        fun toneColor(t: FloatingTone): Int = when (t) {
            FloatingTone.URGENT -> Color.parseColor("#C62828")
            FloatingTone.QUICK_DROP -> Color.parseColor("#9A4A00")
            FloatingTone.WARN -> Color.parseColor("#E65100")
            FloatingTone.STALE -> Color.parseColor("#424242")
            FloatingTone.NORMAL -> Color.parseColor("#2E7D32")
        }
    }
}
