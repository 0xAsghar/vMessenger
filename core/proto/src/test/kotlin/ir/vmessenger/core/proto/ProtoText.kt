package ir.vmessenger.core.proto

/** One field as the wire sees it: its number is its identity, its type and cardinality its encoding. */
internal data class ProtoField(val name: String, val type: String, val repeated: Boolean, val oneof: String?)

/** What a set of .proto files puts on the wire: fields by number, reserved numbers, enum values by number. */
internal class ProtoSchema {
    val messages = mutableMapOf<String, MutableMap<Int, ProtoField>>()
    val reserved = mutableMapOf<String, MutableSet<Int>>()
    val enums = mutableMapOf<String, MutableMap<Int, String>>()
}

/**
 * Just enough of a .proto parser to compare two versions of this project's schema: messages
 * (nested too), oneofs, maps, reserved numbers and enums. Options and imports are skipped; nothing
 * else appears in these files, and anything that did would fail the parse rather than be missed.
 */
internal class ProtoText private constructor(text: String, private val into: ProtoSchema) {
    private val tokens = TOKEN.findAll(stripComments(text)).map { it.value }.toList()
    private var at = 0
    private var pkg = ""

    private fun next(): String = tokens[at++]

    private fun peek(): String = tokens[at]

    private fun expect(token: String) = check(next() == token) { "expected '$token' before token $at" }

    private fun skipStatement() {
        while (next() != ";") Unit
    }

    private fun file() {
        while (at < tokens.size) {
            when (next()) {
                "syntax", "import", "option" -> skipStatement()
                "package" -> pkg = next().also { expect(";") }
                "message" -> message(pkg)
                "enum" -> enum(pkg)
                else -> error("unexpected top-level token ${tokens[at - 1]}")
            }
        }
    }

    private fun message(prefix: String) {
        val name = "$prefix.${next()}"
        into.messages.getOrPut(name) { mutableMapOf() }
        expect("{")
        while (peek() != "}") {
            when (peek()) {
                "message" -> message(name.also { next() })
                "enum" -> enum(name.also { next() })
                "option" -> skipStatement()
                "reserved" -> reserved(name.also { next() })
                "oneof" -> oneof(name.also { next() })
                else -> field(name, oneof = null)
            }
        }
        expect("}")
    }

    private fun oneof(message: String) {
        val group = next()
        expect("{")
        while (peek() != "}") if (peek() == "option") skipStatement() else field(message, group)
        expect("}")
    }

    private fun reserved(message: String) {
        var token = next()
        while (token != ";") {
            token.toIntOrNull()?.let { into.reserved.getOrPut(message) { mutableSetOf() } += it }
            token = next()
        }
    }

    private fun field(message: String, oneof: String?) {
        var label = next()
        val repeated = label == "repeated"
        if (label == "repeated" || label == "optional") label = next()
        val type = if (label == "map") mapType() else label
        val name = next()
        expect("=")
        val number = next().toInt()
        if (peek() == "[") while (next() != "]") Unit
        expect(";")
        into.messages.getValue(message)[number] = ProtoField(name, type, repeated || type.startsWith("map<"), oneof)
    }

    private fun mapType(): String {
        expect("<")
        val key = next()
        expect(",")
        val value = next()
        expect(">")
        return "map<$key,$value>"
    }

    private fun enum(prefix: String) {
        val name = "$prefix.${next()}"
        val values = into.enums.getOrPut(name) { mutableMapOf() }
        expect("{")
        while (peek() != "}") {
            val value = next()
            if (value == "option" || value == "reserved") skipStatement() else enumValue(value, values)
        }
        expect("}")
    }

    private fun enumValue(value: String, values: MutableMap<Int, String>) {
        expect("=")
        values[next().toInt()] = value
        if (peek() == "[") while (next() != "]") Unit
        expect(";")
    }

    companion object {
        private val TOKEN = Regex("\"[^\"]*\"|[A-Za-z_][A-Za-z0-9_.]*|-?\\d+|[{}=;\\[\\]<>,()]")

        fun parse(sources: List<String>): ProtoSchema = ProtoSchema().also { schema ->
            sources.forEach { ProtoText(it, schema).file() }
        }

        private fun stripComments(text: String): String =
            text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("//[^\n]*"), " ")
    }
}
