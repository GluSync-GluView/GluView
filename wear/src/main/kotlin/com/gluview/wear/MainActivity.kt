package com.gluview.wear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.gluview.wear.contract.SnapshotSource
import com.gluview.wear.data.CloudFetcher
import com.gluview.wear.data.SnapshotStore
import com.gluview.wear.ui.Display
import com.gluview.wear.watchface.FacePusher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tela do GluView no relógio (Fase 7): valor, seta/variação, hora da medição, minigráfico, origem
 * e "Sincronizar agora" (plano B na nuvem). Sem leitura ainda: pede para abrir o app no celular.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { GlucoseScreen() } }
    }

    companion object {
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}

@Composable
private fun GlucoseScreen() {
    val context = LocalContext.current
    val snapshot by SnapshotStore.state(context).collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Plano 16 (W3): visor do GluView instalado mas não ativo → oferece "Usar". A permissão de
    // ativar só pode ser pedida uma vez; negada, a tela ensina o caminho manual no seletor.
    var faceInactive by remember { mutableStateOf(false) }
    var faceMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { faceInactive = FacePusher.installedButInactive(context) }
    val askActivate = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        FacePusher.markActivationAsked(context)
        scope.launch {
            val ok = granted && FacePusher.activate(context)
            faceInactive = !ok
            faceMessage = context.getString(if (ok) R.string.face_activated else R.string.face_manual)
        }
    }

    // Relógio da tela: reavalia a idade a cada 30 s (o valor vira "--" sem precisar de leitura nova).
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    val graph = remember(snapshot?.measuredAtMs) { SnapshotStore.graphBitmap(context) }
    val s = snapshot
    val stale = s == null || s.isStale(now)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (s == null) {
            Text(stringResource(R.string.no_data), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.no_data_detail), textAlign = TextAlign.Center, fontSize = 12.sp, color = Color(Display.COLOR_TEXT_DIM))
        } else {
            Text(s.displayValue(now), fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color(Display.zoneColor(s, now)))
            Text(
                if (stale) stringResource(R.string.stale)
                else listOf(s.arrow(now), s.deltaText(now), Display.unitLabel(s)).filter { it.isNotEmpty() }.joinToString("  "),
                fontSize = 16.sp, color = if (stale) Color(Display.COLOR_STALE) else Color.White,
            )
            if (!stale && graph != null) {
                Image(graph.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().height(64.dp))
            }
            val source = stringResource(if (s.source == SnapshotSource.CLOUD) R.string.via_cloud else R.string.via_phone)
            Text("${stringResource(R.string.measured_at, Display.measuredTime(s))} · $source",
                fontSize = 12.sp, color = Color(Display.COLOR_TEXT_DIM))
        }

        if (faceInactive) {
            Chip(
                onClick = {
                    if (FacePusher.activationAsked(context)) {
                        scope.launch {
                            val ok = FacePusher.activate(context)
                            faceInactive = !ok
                            faceMessage = context.getString(if (ok) R.string.face_activated else R.string.face_manual)
                        }
                    } else askActivate.launch("com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE")
                },
                label = { ChipLabel(stringResource(R.string.face_use)) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        faceMessage?.let { Text(it, fontSize = 11.sp, textAlign = TextAlign.Center, color = Color(Display.COLOR_TEXT_DIM)) }

        Chip(
            onClick = {
                if (syncing) return@Chip
                syncing = true
                scope.launch {
                    syncMessage = when (CloudFetcher.fetch(context)) {
                        CloudFetcher.Result.Updated, CloudFetcher.Result.NotNewer -> context.getString(R.string.sync_ok)
                        CloudFetcher.Result.NoToken -> context.getString(R.string.sync_no_token)
                        is CloudFetcher.Result.Failed -> context.getString(R.string.sync_failed)
                    }
                    now = System.currentTimeMillis()
                    syncing = false
                }
            },
            label = { ChipLabel(stringResource(if (syncing) R.string.syncing else R.string.sync_now)) },
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier.padding(top = 6.dp),
        )
        syncMessage?.let { Text(it, fontSize = 11.sp, textAlign = TextAlign.Center, color = Color(Display.COLOR_TEXT_DIM)) }
        Text(stringResource(R.string.not_medical), fontSize = 10.sp, color = Color(Display.COLOR_TEXT_DIM))
    }
}

/**
 * Rótulo de botão centralizado e com no máximo 2 linhas: com fonte grande (acessibilidade) e textos
 * em português o rótulo quebrava alinhado à esquerda, fora do padrão da tela redonda (Fase 10).
 */
@Composable
private fun ChipLabel(text: String) {
    Text(text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
}
