package com.gluview.app.data.libre2

import android.nfc.Tag
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ponte entre a Activity (dona do reader-mode do NFC) e o ViewModel de ativação do Libre 2.
 *
 * O reader-mode do NFC é escopo de Activity, mas a lógica de ativação vive no ViewModel/data. Este
 * singleton desacopla os dois:
 *   - A tela/VM liga o reader-mode via [setReaderMode]; a MainActivity observa [readerMode] e
 *     chama `NfcAdapter.enableReaderMode`/`disableReaderMode`.
 *   - Quando a Activity captura uma [Tag], chama [onTag]; o VM coleta de [tags] e ativa o sensor.
 */
@Singleton
class Libre2NfcController @Inject constructor() {
    private val _readerMode = MutableStateFlow(false)
    val readerMode: StateFlow<Boolean> = _readerMode.asStateFlow()

    private val _tags = MutableSharedFlow<Tag>(extraBufferCapacity = 1)
    val tags: SharedFlow<Tag> = _tags.asSharedFlow()

    /** Liga/desliga o reader-mode do NFC (a MainActivity aplica no adapter). */
    fun setReaderMode(on: Boolean) { _readerMode.value = on }

    /** Chamado pela MainActivity quando uma tag é lida. */
    fun onTag(tag: Tag) { _tags.tryEmit(tag) }
}
