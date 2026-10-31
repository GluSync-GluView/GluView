package com.gluview.app.ui.screen.donation

import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.gluview.app.R
import com.gluview.app.data.donation.DonationAmount
import com.gluview.app.data.donation.PendingDonation
import com.gluview.app.ui.components.rememberBase64Image
import com.gluview.app.ui.components.kit.AppDialog
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.ListSkeleton
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import java.util.Date

/** Ações da tela — separadas do estado para as capturas do harness visual. */
data class DonationActions(
    val onAccept: () -> Unit = {},
    val onDecline: () -> Unit = {},
    val onNoticeAgain: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onCreate: (Int) -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onGiveUp: () -> Unit = {},
    val onChangeAmount: () -> Unit = {},
    val onBack: () -> Unit = {}
)

@Composable
fun DonationScreen(onBack: () -> Unit, viewModel: DonationViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Voltando do app do banco: confere o Pix na hora.
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose {}
    }

    // O agradecimento é uma notificação: no Android 13+ ela precisa da permissão. Pedida no "Gerar
    // Pix" (gesto da pessoa); negar não muda nada, o agradecimento também aparece na tela.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) runCatching { notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    }

    // Fechar no meio (aviso ou escolha do valor) também mostra que o GluView é gratuito. Com o Pix
    // na tela, voltar só sai: o acompanhamento continua e a notificação chega se pagar.
    BackHandler(enabled = ui.step == DonationStep.Amount) { viewModel.decline() }

    DonationContent(
        ui = ui,
        actions = DonationActions(
            onAccept = viewModel::accept,
            onDecline = viewModel::decline,
            onNoticeAgain = viewModel::showNoticeAgain,
            onRetry = viewModel::load,
            onCreate = { cents ->
                // Só o modo Mercado Pago avisa por notificação; com chave fixa não há o que avisar.
                if (ui.range?.isStatic == false) askNotificationPermission()
                viewModel.create(cents)
            },
            onCopy = { code -> clipboard.setText(AnnotatedString(code)) },
            onGiveUp = viewModel::giveUp,
            onChangeAmount = viewModel::accept,
            onBack = onBack
        ),
        validate = viewModel::validate
    )
}

@Composable
internal fun DonationContent(
    ui: DonationUi,
    actions: DonationActions,
    validate: (String, Int?) -> Int? = { _, p -> p }
) {
    Page(title = stringResource(R.string.donation_title), onBack = actions.onBack) {
        Text(
            stringResource(R.string.donation_lede),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when (val step = ui.step) {
            DonationStep.Loading -> ListSkeleton(rows = 2)

            DonationStep.LoadError -> GluCallout(
                message = stringResource(R.string.donation_error_load),
                tone = Tone.Danger,
                actionLabel = stringResource(R.string.donation_retry),
                onAction = actions.onRetry,
                announce = true
            )

            DonationStep.Unavailable -> GluCallout(
                title = stringResource(R.string.donation_unavailable_title),
                message = stringResource(R.string.donation_unavailable_body),
                tone = Tone.Neutral
            )

            // Fica atrás do diálogo do aviso: só o texto de abertura.
            DonationStep.Notice -> Unit

            DonationStep.Declined -> {
                GluCallout(
                    title = stringResource(R.string.donation_declined_title),
                    message = stringResource(R.string.donation_declined_body),
                    tone = Tone.Info,
                    announce = true
                )
                ResultButtons(actions.onBack, stringResource(R.string.donation_declined_again).takeIf { ui.range != null }, actions.onNoticeAgain)
            }

            DonationStep.Amount -> ui.range?.let { AmountSection(it, ui, actions, validate) }

            is DonationStep.Pix -> PixSection(step.donation, actions)

            is DonationStep.StaticPix -> {
                PixCard(step.amountCents, step.copiaECola, step.qrCodeBase64, actions.onCopy)
                // Sem título "Obrigado pela sua doação!": com chave fixa ninguém sabe se o Pix foi pago.
                GluCallout(
                    message = stringResource(R.string.donation_static_thanks) + "\n\n" + stringResource(R.string.donation_static_free),
                    tone = Tone.Success
                )
                ResultButtons(actions.onBack, stringResource(R.string.donation_static_change_amount), actions.onChangeAmount)
            }

            is DonationStep.Thanks -> {
                GluCallout(
                    title = stringResource(R.string.donation_thanks_title),
                    message = stringResource(R.string.donation_thanks_body, DonationAmount.format(step.amountCents)),
                    tone = Tone.Success,
                    announce = true
                )
                ResultButtons(actions.onBack, null, {})
            }

            DonationStep.Expired -> {
                GluCallout(
                    title = stringResource(R.string.donation_expired_title),
                    message = stringResource(R.string.donation_expired_body),
                    tone = Tone.Neutral,
                    announce = true
                )
                ResultButtons(actions.onBack, stringResource(R.string.donation_expired_again).takeIf { ui.range != null }, actions.onAccept)
            }
        }
    }

    if (ui.step == DonationStep.Notice) {
        AppDialog(
            title = stringResource(R.string.donation_notice_title),
            body = stringResource(R.string.donation_notice_body),
            confirmLabel = stringResource(R.string.donation_notice_accept),
            onConfirm = actions.onAccept,
            // Tocar fora, voltar ou "Agora não": tudo conta como recusa.
            onDismiss = actions.onDecline,
            dismissLabel = stringResource(R.string.donation_notice_decline)
        )
    }
}

@Composable
private fun ResultButtons(onBack: () -> Unit, secondaryLabel: String?, onSecondary: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.donation_back)) }
        if (secondaryLabel != null) {
            TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) { Text(secondaryLabel) }
        }
    }
}

@Composable
private fun AmountSection(
    range: DonationRange,
    ui: DonationUi,
    actions: DonationActions,
    validate: (String, Int?) -> Int?
) {
    var preset by rememberSaveable { mutableStateOf(range.presetCents.getOrNull(1) ?: range.presetCents.firstOrNull()) }
    var other by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val min = DonationAmount.format(range.minCents)
    val max = DonationAmount.format(range.maxCents)

    GluSection(stringResource(R.string.donation_amount_title)) {
        GluCard {
            // Dois por linha: R$ 5 / 10 / 20 / 50 cabem com folga até em fonte grande.
            range.presetCents.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    row.forEach { cents ->
                        FilterChip(
                            selected = other.isBlank() && preset == cents,
                            onClick = { preset = cents; other = ""; invalid = false },
                            label = {
                                Text(
                                    DonationAmount.format(cents),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                                    textAlign = TextAlign.Center
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            OutlinedTextField(
                value = other,
                onValueChange = { other = it; invalid = false },
                label = { Text(stringResource(R.string.donation_amount_other)) },
                singleLine = true,
                isError = invalid,
                supportingText = {
                    Text(
                        if (invalid) stringResource(R.string.donation_amount_invalid, min, max)
                        else stringResource(R.string.donation_amount_hint, min, max)
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    if (ui.createError) {
        GluCallout(message = stringResource(R.string.donation_error_create), tone = Tone.Danger, announce = true)
    }
    Button(
        onClick = {
            val cents = validate(other, preset)
            if (cents == null) invalid = true else actions.onCreate(cents)
        },
        enabled = !ui.creating,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(if (ui.creating) R.string.donation_amount_generating else R.string.donation_amount_generate))
    }
}

@Composable
private fun PixSection(donation: PendingDonation, actions: DonationActions) {
    val context = LocalContext.current
    val expires = remember(donation.expiresAtMs) { DateFormat.getTimeFormat(context).format(Date(donation.expiresAtMs)) }

    PixCard(donation.amountCents, donation.copiaECola, donation.qrCodeBase64, actions.onCopy)
    GluCallout(
        message = stringResource(R.string.donation_pix_waiting) + " " + stringResource(R.string.donation_pix_expires, expires),
        tone = Tone.Info
    )
    OutlinedButton(onClick = actions.onGiveUp, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.donation_pix_give_up))
    }
}

/** QR + copia e cola — igual nos dois modos (chave fixa e Mercado Pago). */
@Composable
private fun PixCard(amountCents: Int, copiaECola: String, qrCodeBase64: String, onCopy: (String) -> Unit) {
    val amount = DonationAmount.format(amountCents)
    val qr = rememberBase64Image(qrCodeBase64)
    var copied by remember(copiaECola) { mutableStateOf(false) }

    GluSection(stringResource(R.string.donation_pix_title, amount), description = stringResource(R.string.donation_pix_instructions)) {
        GluCard {
            // Quem doa pelo próprio celular usa o copia e cola: o botão vem primeiro (com fonte grande
            // o código ocupa meia tela e empurrava o botão para baixo).
            Button(
                onClick = { onCopy(copiaECola); copied = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.donation_pix_copy)) }
            if (copied) {
                Text(
                    stringResource(R.string.donation_pix_copied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            SelectionContainer {
                Text(
                    copiaECola,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (qr != null) {
                // Fundo branco SEMPRE: leitor de banco não lê QR escuro sobre fundo escuro do tema.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = qr,
                        contentDescription = stringResource(R.string.donation_pix_qr_description, amount),
                        modifier = Modifier
                            .widthIn(max = 260.dp)
                            .size(220.dp)
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(Spacing.sm)
                    )
                }
            }
        }
    }
}
