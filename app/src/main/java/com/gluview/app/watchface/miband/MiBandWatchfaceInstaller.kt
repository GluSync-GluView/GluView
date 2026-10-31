package com.gluview.app.watchface.miband

import com.gluview.app.watchface.compiler.WatchfaceInstaller

/**
 * [WatchfaceInstaller] da Mi Band 6 (plano 06_watchfaces_v2_arquitetura.md, Fase 3) — encapsula
 * [MiBandUploader] atrás do contrato genérico "bytes -> enviado com sucesso?". MAC/auth key ficam
 * capturados no construtor (são por-envio, não por-classe, já que dependem da banda pareada), não
 * fazem parte do contrato genérico porque outra plataforma (ex.: Wear OS) não teria os mesmos
 * parâmetros de credencial.
 */
class MiBandWatchfaceInstaller(
    private val uploader: MiBandUploader,
    private val mac: String,
    private val authKey: ByteArray
) : WatchfaceInstaller {
    override suspend fun install(packageBytes: ByteArray, onLog: (String) -> Unit): Boolean =
        uploader.connectAndUpload(mac, authKey, packageBytes, onLog)
}
