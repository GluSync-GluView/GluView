package com.gluview.app.watchface.miband

import com.gluview.app.wearables.huami.HuamiLegacyModel

/** Protocolo de envio de visor (WatchDrip+: FirmwareOperation, FirmwareOperationsNew, FirmwareOperations2020). */
enum class HuamiUpload { ORIGINAL, NEW, P2020 }

/** Cabeçalho do .bin do visor (WatchDrip+ `Header.getHeader`). */
enum class HuamiHeader { MIBAND4, MIBAND5, VERGE, VERGE2, GTS2_MINI }

/** Codificação da imagem principal (WatchDrip+ `WatchFaceGenerator.genWatchFace`). */
enum class HuamiImage { MIBAND_PALETTE, BIP_PALETTE, RGB, TRANSPARENT_RGB, RGB_GTS2_MINI }

/**
 * Plano 21, F7.2 — tudo o que muda de um modelo Huami/Amazfit para outro no envio de visor, tirado
 * do WatchDrip+ (GPLv3, `_research/wd_src/.../watch/miband`): `MiBandType` (flags de autenticação,
 * prefixo do modelo de visor), `Header.getHeader`, `WatchFaceGenerator` (imagem e compressão) e
 * `MiBandService` + `Sequence/SequenceState*` (protocolo e ordem dos comandos). Assume firmware
 * atualizado (onde o WatchDrip escolhe o protocolo pela versão, vale o ramo da versão nova).
 *
 * Mi Band 6 = o caminho que já funcionava com a pulseira real do Felipe (não muda nada).
 */
data class HuamiProfile(
    val model: HuamiLegacyModel,
    val assetPrefix: String,
    val header: HuamiHeader,
    val image: HuamiImage,
    /** Compressão QuickLZ (família Verge, menos o GTS 2 mini). */
    val compress: Boolean,
    /** Bytes do começo copiados sem comprimir (Verge 2 / T-Rex Pro: VERGE2_HEADERLEN = 40). */
    val uncompressedHead: Int,
    val upload: HuamiUpload,
    val authFlags: Byte,
    val cryptFlags: Byte,
    /** Manda o id do visor (0x39) na configuração. */
    val sendWatchfaceId: Boolean,
    /** Manda o modo noturno agendado na configuração. */
    val sendNightMode: Boolean,
    /** GTS 2 mini: o id do visor vai DEPOIS do pedido de parâmetros (d0). */
    val watchfaceIdAfterParams: Boolean = false,
    /** Mi Band 5 / GTR 42: comando 0xA0 depois do preparo (protocolo New). */
    val unknownA0: Boolean = false
) {
    companion object {
        private const val AUTH = 0x08.toByte()          // OperationCodes.AUTH_BYTE
        private const val AUTH_ALT = 0x00.toByte()      // AUTH_BYTE_ALTERNATIVE
        private const val CRYPT = 0x00.toByte()         // AUTH_CRYPT_FLAG
        private const val CRYPT_ALT = 0x80.toByte()     // AUTH_CRYPT_FLAG_ALTERNATIVE
        const val VERGE2_HEADERLEN = 40

        private fun p2020(m: HuamiLegacyModel, prefix: String, header: HuamiHeader, image: HuamiImage, compress: Boolean,
                          head: Int, wfId: Boolean, night: Boolean, wfIdAfter: Boolean = false) =
            HuamiProfile(m, prefix, header, image, compress, head, HuamiUpload.P2020, AUTH_ALT, CRYPT_ALT, wfId, night, wfIdAfter)

        /** Perfil do modelo, ou null se ele não recebe visor (Mi Band 2/3, GTS 1ª geração). */
        fun of(model: HuamiLegacyModel): HuamiProfile? = when (model) {
            HuamiLegacyModel.MI_BAND_6 ->
                p2020(model, "miband6", HuamiHeader.MIBAND5, HuamiImage.MIBAND_PALETTE, false, 0, wfId = true, night = true)
            HuamiLegacyModel.MI_BAND_5, HuamiLegacyModel.AMAZFIT_BAND_5 ->
                HuamiProfile(model, "miband5", HuamiHeader.MIBAND5, HuamiImage.MIBAND_PALETTE, false, 0, HuamiUpload.NEW,
                    AUTH_ALT, CRYPT_ALT, sendWatchfaceId = true, sendNightMode = true, unknownA0 = true)
            HuamiLegacyModel.MI_BAND_4 ->
                HuamiProfile(model, "miband4", HuamiHeader.MIBAND4, HuamiImage.MIBAND_PALETTE, false, 0, HuamiUpload.NEW,
                    AUTH_ALT, CRYPT_ALT, sendWatchfaceId = false, sendNightMode = true)
            HuamiLegacyModel.AMAZFIT_GTR_47 ->   // SequenceStateVergeNew (firmware ≥ 1.3.7.16)
                p2020(model, "amazfit_gtr", HuamiHeader.VERGE, HuamiImage.RGB, true, 0, wfId = false, night = false)
            HuamiLegacyModel.AMAZFIT_GTR_42 ->   // SequenceStateVergeOld = sequência da Mi Band 5
                HuamiProfile(model, "amazfit_gtr42", HuamiHeader.VERGE, HuamiImage.RGB, true, 0, HuamiUpload.NEW,
                    AUTH_ALT, CRYPT_ALT, sendWatchfaceId = true, sendNightMode = true, unknownA0 = true)
            HuamiLegacyModel.AMAZFIT_GTR2 ->
                p2020(model, "amazfit_gtr2", HuamiHeader.VERGE2, HuamiImage.TRANSPARENT_RGB, true, VERGE2_HEADERLEN, wfId = true, night = false)
            HuamiLegacyModel.AMAZFIT_GTS2 ->
                p2020(model, "amazfit_gts2", HuamiHeader.VERGE2, HuamiImage.TRANSPARENT_RGB, true, VERGE2_HEADERLEN, wfId = true, night = false)
            HuamiLegacyModel.AMAZFIT_T_REX_PRO ->
                p2020(model, "amazfit_trex_pro", HuamiHeader.VERGE2, HuamiImage.TRANSPARENT_RGB, true, VERGE2_HEADERLEN, wfId = true, night = false)
            HuamiLegacyModel.AMAZFIT_GTS2_MINI ->
                p2020(model, "amazfit_gts2_mini", HuamiHeader.GTS2_MINI, HuamiImage.RGB_GTS2_MINI, false, 0,
                    wfId = true, night = false, wfIdAfter = true)
            HuamiLegacyModel.AMAZFIT_BIP_S, HuamiLegacyModel.AMAZFIT_BIP_S_LITE ->   // SequenceStateBipS (firmware novo)
                p2020(model, "bip_s", HuamiHeader.MIBAND4, HuamiImage.BIP_PALETTE, false, 0, wfId = false, night = false)
            HuamiLegacyModel.AMAZFIT_BIP ->      // FirmwareOperation + SequenceStateBip; auth "clássica"
                HuamiProfile(model, "bip", HuamiHeader.MIBAND4, HuamiImage.BIP_PALETTE, false, 0, HuamiUpload.ORIGINAL,
                    AUTH, CRYPT, sendWatchfaceId = false, sendNightMode = true)
            HuamiLegacyModel.AMAZFIT_BIP_LITE ->  // mesma do Bip, mas com as flags alternativas
                HuamiProfile(model, "bip", HuamiHeader.MIBAND4, HuamiImage.BIP_PALETTE, false, 0, HuamiUpload.ORIGINAL,
                    AUTH_ALT, CRYPT_ALT, sendWatchfaceId = false, sendNightMode = true)
            HuamiLegacyModel.MI_BAND_2, HuamiLegacyModel.MI_BAND_3, HuamiLegacyModel.AMAZFIT_GTS -> null
        }

        val MI_BAND_6: HuamiProfile = of(HuamiLegacyModel.MI_BAND_6)!!
    }
}
