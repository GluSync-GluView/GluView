package com.gluview.mirror

/**
 * JSON mínimo (escrever e ler) para o espelho e o servidor local — sem dependência, para o
 * :mirror-core continuar Kotlin puro e testável na JVM. Lê objetos, listas, texto, número,
 * verdadeiro/falso e nulo; números saem como Double (ou Long, se inteiros).
 */
object Json {

    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> sb.append(if (v.isFinite()) num(v) else "null")
            is Float -> sb.append(if (v.isFinite()) num(v.toDouble()) else "null")
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString()); sb.append(':'); append(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) { if (!first) sb.append(','); first = false; append(sb, item) }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun num(d: Double): String =
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    class ParseException(msg: String) : Exception(msg)

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw ParseException("texto sobrando na posição ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            if (i >= s.length) throw ParseException("fim inesperado")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw ParseException("caractere '$c' em $i")
            }
        }
        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw ParseException("esperado $word em $i")
            i += word.length; return v
        }
        fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws(); if (i >= s.length || s[i] != '"') throw ParseException("chave esperada em $i")
                val k = str(); ws()
                if (i >= s.length || s[i] != ':') throw ParseException("':' esperado em $i")
                i++
                m[k] = value(); ws()
                if (i >= s.length) throw ParseException("objeto sem fim")
                when (s[i]) { ',' -> i++; '}' -> { i++; return m }; else -> throw ParseException("',' ou '}' em $i") }
            }
        }
        fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l.add(value()); ws()
                if (i >= s.length) throw ParseException("lista sem fim")
                when (s[i]) { ',' -> i++; ']' -> { i++; return l }; else -> throw ParseException("',' ou ']' em $i") }
            }
        }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw ParseException("texto sem fim")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw ParseException("escape sem fim")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw ParseException("\\u curto")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> throw ParseException("escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun number(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw ParseException("número inválido '$t'")
        }
    }
}

/** Atalhos para ler mapas vindos do [Json.parse]. */
internal fun Map<*, *>.str(k: String): String? = this[k] as? String
internal fun Map<*, *>.long(k: String): Long? = (this[k] as? Number)?.toLong()
internal fun Map<*, *>.dbl(k: String): Double? = (this[k] as? Number)?.toDouble()
internal fun Map<*, *>.bool(k: String): Boolean? = this[k] as? Boolean
