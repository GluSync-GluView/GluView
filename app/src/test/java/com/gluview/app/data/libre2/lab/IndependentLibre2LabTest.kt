package com.gluview.app.data.libre2.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndependentLibre2LabTest {
    @Test
    fun `invalid NFC geometry is rejected before a handshake starts`() {
        val verdict = IndependentLibre2Lab.inspect(
            transcript(
                uid = ByteArray(7),
                fram = ByteArray(IndependentLibre2Lab.FRAM_BYTES - 1)
            )
        )

        assertTrue(verdict is LabVerdict.Rejected)
        val errors = (verdict as LabVerdict.Rejected).errors
        assertTrue(errors.any { it.startsWith("uid must contain 8 bytes") })
        assertTrue(errors.any { it.startsWith("fram must contain 344 bytes") })
    }

    @Test
    fun `structurally complete transcript never falls back to native or placeholder decrypt`() {
        val verdict = IndependentLibre2Lab.inspect(transcript())

        assertTrue(verdict is LabVerdict.AwaitingIndependentAlgorithms)
        assertEquals(
            setOf(
                AlgorithmStage.NFC_PROCESS_SCAN,
                AlgorithmStage.BLE_HANDSHAKE,
                AlgorithmStage.F002_DECODER
            ),
            (verdict as LabVerdict.AwaitingIndependentAlgorithms).stages
        )
    }

    @Test
    fun `phase3 must be complete before a transcript can enter the decoder stage`() {
        val verdict = IndependentLibre2Lab.inspect(transcript(phase3 = ByteArray(24)))

        assertTrue(verdict is LabVerdict.Rejected)
        assertTrue((verdict as LabVerdict.Rejected).errors.single().startsWith("phase3 must contain 25 bytes"))
    }

    @Test
    fun `live HCI vector preserves only the observed two byte f002 header`() {
        // Captured from the S23 FE HCI snoop and paired with LIBRE2_CPP P2(773) output.
        val ciphertext = hex(
            "F30ADEB620B487A7AAE2C7CD71CB9548FD214D99C3F23EE2A1A21CA035737D56" +
                "1FFE9581BF5DCAEBE09AE97A3D3B"
        )
        val p2OutputPrefix = hex("F30A68C4EA0D5944E0115B04E4115744E9115804F2115B44E6114EC4EA115484")

        assertEquals(46, ciphertext.size)
        assertEquals(32, p2OutputPrefix.size)
        assertTrue(ciphertext.copyOfRange(0, 2).contentEquals(p2OutputPrefix.copyOfRange(0, 2)))
        assertFalse(ciphertext.copyOfRange(2, p2OutputPrefix.size).contentEquals(p2OutputPrefix.copyOfRange(2, p2OutputPrefix.size)))
    }

    private fun transcript(
        uid: ByteArray = hex("0102030405A407E0"),
        fram: ByteArray = ByteArray(IndependentLibre2Lab.FRAM_BYTES),
        phase3: ByteArray = hex("6DF0ECAC6A63D630E38173D7CA20021B82FBBFCAE0F4B2097F")
    ) = CaptureTranscript(
        uid = uid,
        patchInfo = hex("2B0A3A081FE1"),
        fram = fram,
        challenge = hex("64EBE16E9113CB7F00000004C064"),
        authResponse = hex("1F1678CC44298E2BE4D94007F5E78530B8967E"),
        phase3 = phase3,
        f002 = hex("1505358BEBBAF9509AB7F2CB64BD982ED1C1209FB4C38AB06BE34FB09BC9AAF950B8FCC5B740DE56A257937E6059")
    )

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
