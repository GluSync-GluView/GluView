package com.gluview.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.diary.DiaryType
import com.gluview.app.ui.screen.diary.QuickAddFlow
import com.gluview.app.ui.theme.GluViewTheme
import com.gluview.app.ui.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Registro rápido no diário sem abrir o app (pedido do Felipe, 10/11/2026): vem do widget e da
 * notificação fixa, mostra só a folha de registro por cima do que estiver na tela e fecha ao salvar.
 * Não exportada — só PendingIntent do próprio app chega aqui.
 */
@AndroidEntryPoint
class QuickAddActivity : AppCompatActivity() {

    @Inject lateinit var preferences: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialType = intent.getStringExtra(EXTRA_TYPE)?.let { k -> DiaryType.entries.firstOrNull { it.key == k } }
        val initialTheme = runCatching { ThemeMode.valueOf(runBlocking { preferences.themeMode.first() }) }.getOrDefault(ThemeMode.SYSTEM)
        setContent {
            val themeMode = preferences.themeMode.map { runCatching { ThemeMode.valueOf(it) }.getOrDefault(ThemeMode.SYSTEM) }
                .collectAsStateWithLifecycle(initialValue = initialTheme).value
            GluViewTheme(themeMode = themeMode) {
                QuickAddFlow(
                    initialType = initialType,
                    onDone = { finish() },
                    onOpenMealEditor = { openApp("gluview://diary?type=meal") },
                    onOpenCatalog = { openApp("gluview://diary") }
                )
            }
        }
    }

    private fun openApp(uri: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri), this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    companion object {
        const val EXTRA_TYPE = "type"
    }
}
