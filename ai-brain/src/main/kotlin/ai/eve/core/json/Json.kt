package ai.eve.core.json

/**
 * Minimal, dependency-free JSON model used by the provider clients so that they run on
 * any JVM (Android, Windows, robot host) and can be unit-tested without Android stubs.
 * Numbers keep their original text so that values round-trip exactly.
 */
sealed interface Json {
    data class Obj(val fields: LinkedHashMap<String, Json> = LinkedHashMap()) : Json {
        operator fun get(key: String): Json? = fields[key]
        fun put(key: String, value: Json?): Obj { if (value != null) fields[key] = value; return this }
        fun put(key: String, value: String?): Obj = put(key, value?.let(::Str))
        fun put(key: String, value: Number): Obj = put(key, Num(value.toString()))
        fun put(key: String, value: Boolean): Obj = put(key, Bool(value))
        fun str(key: String): String? = (fields[key] as? Str)?.value
        fun obj(key: String): Obj? = fields[key] as? Obj
        fun arr(key: String): Arr? = fields[key] as? Arr
        fun int(key: String): Int? = (fields[key] as? Num)?.raw?.toDoubleOrNull()?.toInt()
        fun bool(key: String): Boolean? = (fields[key] as? Bool)?.value
        val keys: Set<String> get() = fields.keys
    }
    data class Arr(val items: MutableList<Json> = mutableListOf()) : Json, Iterable<Json> {
        fun add(value: Json): Arr { items += value; return this }
        override fun iterator() = items.iterator()
        val size get() = items.size
        fun objects(): List<Obj> = items.filterIsInstance<Obj>()
    }
    data class Str(val value: String) : Json
    data class Num(val raw: String) : Json
    data class Bool(val value: Boolean) : Json
    data object Null : Json

    /** Value as plain text, as tool arguments expect ("7" for 7, "true" for true). */
    fun asText(): String = when (this) {
        is Str -> value
        is Num -> raw
        is Bool -> value.toString()
        Null -> ""
        else -> stringify(this)
    }

    companion object {
        fun obj(vararg pairs: Pair<String, Any?>): Obj = Obj().also { o -> pairs.forEach { (k, v) -> o.put(k, of(v)) } }
        fun arr(vararg items: Any?): Arr = Arr(items.mapNotNull(::of).toMutableList())
        fun of(v: Any?): Json? = when (v) {
            null -> null
            is Json -> v
            is String -> Str(v)
            is Boolean -> Bool(v)
            is Number -> Num(v.toString())
            is Map<*, *> -> Obj().also { o -> v.forEach { (k, x) -> o.put(k.toString(), of(x)) } }
            is Iterable<*> -> Arr(v.mapNotNull(::of).toMutableList())
            else -> Str(v.toString())
        }

        fun parse(text: String): Json = Parser(text).parseDocument()
        fun parseObj(text: String): Obj = parse(text) as? Obj ?: throw JsonException("Expected a JSON object")

        fun stringify(v: Json): String = StringBuilder().also { write(v, it) }.toString()

        private fun write(v: Json, sb: StringBuilder) {
            when (v) {
                is Obj -> {
                    sb.append('{')
                    var first = true
                    for ((k, x) in v.fields) {
                        if (!first) sb.append(','); first = false
                        quote(k, sb); sb.append(':'); write(x, sb)
                    }
                    sb.append('}')
                }
                is Arr -> {
                    sb.append('[')
                    v.items.forEachIndexed { i, x -> if (i > 0) sb.append(','); write(x, sb) }
                    sb.append(']')
                }
                is Str -> quote(v.value, sb)
                is Num -> sb.append(v.raw)
                is Bool -> sb.append(v.value)
                Null -> sb.append("null")
            }
        }

        private fun quote(s: String, sb: StringBuilder) {
            sb.append('"')
            for (c in s) when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
            sb.append('"')
        }
    }
}

class JsonException(message: String) : Exception(message)

private class Parser(private val s: String) {
    private var i = 0

    fun parseDocument(): Json {
        val v = value()
        ws()
        if (i != s.length) fail("Trailing characters")
        return v
    }

    private fun fail(msg: String): Nothing = throw JsonException("$msg at offset $i")

    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun value(): Json {
        ws()
        if (i >= s.length) fail("Unexpected end")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> Json.Str(str())
            't' -> literal("true", Json.Bool(true))
            'f' -> literal("false", Json.Bool(false))
            'n' -> literal("null", Json.Null)
            else -> if (c == '-' || c.isDigit()) num() else fail("Unexpected character '$c'")
        }
    }

    private fun literal(word: String, v: Json): Json {
        if (!s.startsWith(word, i)) fail("Expected $word")
        i += word.length
        return v
    }

    private fun obj(): Json.Obj {
        i++ // {
        val o = Json.Obj()
        ws()
        if (i < s.length && s[i] == '}') { i++; return o }
        while (true) {
            ws()
            if (i >= s.length || s[i] != '"') fail("Expected key")
            val k = str()
            ws()
            if (i >= s.length || s[i] != ':') fail("Expected ':'")
            i++
            o.fields[k] = value()
            ws()
            if (i >= s.length) fail("Unterminated object")
            when (s[i]) { ',' -> i++; '}' -> { i++; return o }; else -> fail("Expected ',' or '}'") }
        }
    }

    private fun arr(): Json.Arr {
        i++ // [
        val a = Json.Arr()
        ws()
        if (i < s.length && s[i] == ']') { i++; return a }
        while (true) {
            a.items += value()
            ws()
            if (i >= s.length) fail("Unterminated array")
            when (s[i]) { ',' -> i++; ']' -> { i++; return a }; else -> fail("Expected ',' or ']'") }
        }
    }

    private fun str(): String {
        i++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) fail("Unterminated string")
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= s.length) fail("Bad escape")
                    when (val e = s[i++]) {
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                        'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) fail("Bad unicode escape")
                            sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail("Bad unicode escape"))
                            i += 4
                        }
                        else -> fail("Bad escape '\\$e'")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun num(): Json.Num {
        val start = i
        if (s[i] == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
        val raw = s.substring(start, i)
        raw.toDoubleOrNull() ?: fail("Bad number")
        return Json.Num(raw)
    }
}
