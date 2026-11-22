package com.gluview.mirror

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Quadro na rede: `[tamanho:int32][versão:1][idPar:16][contador:int64][cifrado]`. O cabeçalho
 * (versão + idPar + contador) é o dado associado da cifra. O id do par vai às claras só para quem
 * recebe achar a chave; sem a chave nada é legível nem forjável.
 *
 * Em cada conexão o contador de cada lado começa em 1 e só sobe: quadro repetido ou fora de ordem
 * derruba a conexão. A sessão ainda é amarrada a um desafio aleatório do seguidor (hello → welcome),
 * então gravar e repetir uma conexão antiga não engana o seguidor.
 */
object MirrorProtocol {
    const val VERSION: Byte = 1
    const val MAX_FRAME = 512 * 1024
    private const val HEADER = 1 + MirrorCrypto.PAIR_ID_BYTES + 8

    fun header(pairId: ByteArray, seq: Long): ByteArray =
        ByteBuffer.allocate(HEADER).put(VERSION).put(pairId).putLong(seq).array()

    fun writeFrame(out: DataOutputStream, pairId: ByteArray, key: ByteArray, seq: Long, message: Map<String, Any?>) {
        val h = header(pairId, seq)
        val sealed = MirrorCrypto.seal(key, h, Json.write(message).toByteArray(Charsets.UTF_8))
        synchronized(out) {
            out.writeInt(h.size + sealed.size)
            out.write(h); out.write(sealed); out.flush()
        }
    }

    class Raw(val pairId: ByteArray, val seq: Long, val header: ByteArray, val sealed: ByteArray)

    /** Lê um quadro ainda cifrado (quem lê decide a chave pelo [Raw.pairId]). */
    fun readRaw(input: DataInputStream): Raw {
        val len = input.readInt()
        if (len <= HEADER || len > MAX_FRAME) throw IOException("quadro com tamanho inválido: $len")
        val buf = ByteArray(len)
        input.readFully(buf)
        if (buf[0] != VERSION) throw IOException("versão do espelho não suportada: ${buf[0]}")
        val pairId = buf.copyOfRange(1, 1 + MirrorCrypto.PAIR_ID_BYTES)
        val seq = ByteBuffer.wrap(buf, 1 + MirrorCrypto.PAIR_ID_BYTES, 8).long
        return Raw(pairId, seq, buf.copyOfRange(0, HEADER), buf.copyOfRange(HEADER, len))
    }

    fun open(raw: Raw, key: ByteArray): Map<*, *> {
        val plain = MirrorCrypto.open(key, raw.header, raw.sealed)
        return Json.parse(plain.toString(Charsets.UTF_8)) as? Map<*, *> ?: throw IOException("mensagem não é objeto")
    }
}

/**
 * O que o paciente manda junto do "bem-vindo": nome, limites, unidade e os endereços pelos quais
 * pode ser alcançado agora ([MirrorCandidate], F2.4).
 */
data class MirrorMeta(
    val patientName: String,
    val thresholds: MirrorThresholds,
    val unit: String,
    val candidates: List<MirrorCandidate> = emptyList()
)
