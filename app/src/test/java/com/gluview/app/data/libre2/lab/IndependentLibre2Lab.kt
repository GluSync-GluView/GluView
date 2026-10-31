package com.gluview.app.data.libre2.lab

/**
 * Sandbox-only contract for replacing the three Abbott native stages.
 *
 * This code intentionally has no dependency on Libre2Native, AbbottBridge, or the
 * experimental XOR stream. A complete transcript may be structurally valid and still
 * not be decodable: only a verified independent implementation may advance a stage.
 */
internal object IndependentLibre2Lab {
    const val UID_BYTES = 8
    const val PATCH_INFO_BYTES = 6
    const val FRAM_BYTES = 344
    const val CHALLENGE_BYTES = 14
    const val AUTH_RESPONSE_BYTES = 19
    const val PHASE3_BYTES = 25
    const val F002_BYTES = 46

    fun inspect(transcript: CaptureTranscript): LabVerdict {
        val errors = buildList {
            requireSize("uid", transcript.uid, UID_BYTES)
            requireSize("patchInfo", transcript.patchInfo, PATCH_INFO_BYTES)
            requireSize("fram", transcript.fram, FRAM_BYTES)
            requireSize("challenge", transcript.challenge, CHALLENGE_BYTES)
            requireSize("authResponse", transcript.authResponse, AUTH_RESPONSE_BYTES)
            requireSize("phase3", transcript.phase3, PHASE3_BYTES)
            requireSize("f002", transcript.f002, F002_BYTES)
        }
        if (errors.isNotEmpty()) return LabVerdict.Rejected(errors)

        // A byte transcript alone cannot establish a session. The P1/P2 context is
        // intentionally not reconstructed from a stale capture or a guessed keystream.
        return LabVerdict.AwaitingIndependentAlgorithms(
            setOf(
                AlgorithmStage.NFC_PROCESS_SCAN,
                AlgorithmStage.BLE_HANDSHAKE,
                AlgorithmStage.F002_DECODER
            )
        )
    }

    private fun MutableList<String>.requireSize(name: String, value: ByteArray, expected: Int) {
        if (value.size != expected) add("$name must contain $expected bytes, received ${value.size}")
    }
}

internal data class CaptureTranscript(
    val uid: ByteArray,
    val patchInfo: ByteArray,
    val fram: ByteArray,
    val challenge: ByteArray,
    val authResponse: ByteArray,
    val phase3: ByteArray,
    val f002: ByteArray
)

internal enum class AlgorithmStage {
    NFC_PROCESS_SCAN,
    BLE_HANDSHAKE,
    F002_DECODER
}

internal sealed interface LabVerdict {
    data class Rejected(val errors: List<String>) : LabVerdict
    data class AwaitingIndependentAlgorithms(val stages: Set<AlgorithmStage>) : LabVerdict
}

/** Replaces native `processScan` only after its algorithm is independently established. */
internal interface IndependentNfcProcessor {
    fun processScan(uid: ByteArray, patchInfo: ByteArray, fram: ByteArray): IndependentSensorState
}

/** Replaces P1(28960), P2(6505), and V1(29465) only after their protected state is understood. */
internal interface IndependentBleHandshake {
    fun establish(state: IndependentSensorState, challenge: ByteArray): VerifiedBleSession
}

/** Replaces P2(773) only when supplied with a session verified by [IndependentBleHandshake]. */
internal interface IndependentF002Decoder {
    fun decode(session: VerifiedBleSession, frame: ByteArray): IndependentGlucoseFrame
}

internal data class IndependentSensorState(
    val sensorId: ByteArray,
    val activationEpochSeconds: Long
)

internal data class VerifiedBleSession(
    val sessionId: Long
)

internal data class IndependentGlucoseFrame(
    val glucoseMgdl: Int,
    val rateMgdlPerMinute: Double,
    val ageMinutes: Int
)
