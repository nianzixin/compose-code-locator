package dev.codelocator.studio.client

internal sealed interface JsonValue

internal data class JsonObject(
    val entries: Map<String, JsonValue>,
) : JsonValue {
    fun value(key: String): JsonValue? = entries[key]

    fun array(key: String): List<JsonValue> {
        return (entries[key] as? JsonArray)?.values.orEmpty()
    }

    fun stringOrNull(key: String): String? {
        return (entries[key] as? JsonString)?.value
    }

    fun intOrNull(key: String): Int? {
        return (entries[key] as? JsonNumber)?.raw?.toIntOrNull()
    }

    fun longOrNull(key: String): Long? {
        return (entries[key] as? JsonNumber)?.raw?.toLongOrNull()
    }

    fun floatOrNull(key: String): Float? {
        return (entries[key] as? JsonNumber)?.raw?.toFloatOrNull()
    }
}

internal data class JsonArray(
    val values: List<JsonValue>,
) : JsonValue

internal data class JsonString(
    val value: String,
) : JsonValue

internal data class JsonNumber(
    val raw: String,
) : JsonValue

internal data object JsonNull : JsonValue

internal data class JsonBoolean(
    val value: Boolean,
) : JsonValue

internal object JsonParser {
    fun parse(raw: String): JsonValue {
        return Parser(raw).parse()
    }
}

private class Parser(
    private val raw: String,
) {
    private var index = 0

    fun parse(): JsonValue {
        val value = parseValue()
        skipWhitespace()
        require(index == raw.length) { "Unexpected trailing JSON at index $index" }
        return value
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        require(index < raw.length) { "Unexpected end of JSON" }
        return when (raw[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonString(parseString())
            'n' -> {
                consumeLiteral("null")
                JsonNull
            }
            't' -> {
                consumeLiteral("true")
                JsonBoolean(true)
            }
            'f' -> {
                consumeLiteral("false")
                JsonBoolean(false)
            }
            '-', in '0'..'9' -> parseNumber()
            else -> error("Unexpected JSON token '${raw[index]}' at index $index")
        }
    }

    private fun parseObject(): JsonObject {
        expect('{')
        skipWhitespace()
        if (tryConsume('}')) return JsonObject(emptyMap())

        val entries = linkedMapOf<String, JsonValue>()
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            entries[key] = parseValue()
            skipWhitespace()
            if (tryConsume('}')) break
            expect(',')
        }
        return JsonObject(entries)
    }

    private fun parseArray(): JsonArray {
        expect('[')
        skipWhitespace()
        if (tryConsume(']')) return JsonArray(emptyList())

        val values = mutableListOf<JsonValue>()
        while (true) {
            values += parseValue()
            skipWhitespace()
            if (tryConsume(']')) break
            expect(',')
        }
        return JsonArray(values)
    }

    private fun parseString(): String {
        expect('"')
        val builder = StringBuilder()
        while (index < raw.length) {
            val char = raw[index++]
            when (char) {
                '"' -> return builder.toString()
                '\\' -> builder.append(parseEscape())
                else -> builder.append(char)
            }
        }
        error("Unterminated JSON string")
    }

    private fun parseEscape(): Char {
        require(index < raw.length) { "Unterminated JSON escape" }
        return when (val escaped = raw[index++]) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscape()
            else -> error("Unsupported JSON escape '\\$escaped' at index ${index - 1}")
        }
    }

    private fun parseUnicodeEscape(): Char {
        require(index + 4 <= raw.length) { "Invalid unicode escape at index $index" }
        val hex = raw.substring(index, index + 4)
        index += 4
        return hex.toInt(16).toChar()
    }

    private fun parseNumber(): JsonNumber {
        val start = index
        if (raw[index] == '-') index++
        consumeDigits()
        if (index < raw.length && raw[index] == '.') {
            index++
            consumeDigits()
        }
        if (index < raw.length && (raw[index] == 'e' || raw[index] == 'E')) {
            index++
            if (index < raw.length && (raw[index] == '+' || raw[index] == '-')) index++
            consumeDigits()
        }
        return JsonNumber(raw.substring(start, index))
    }

    private fun consumeDigits() {
        val start = index
        while (index < raw.length && raw[index].isDigit()) index++
        require(index > start) { "Expected digit at index $index" }
    }

    private fun consumeLiteral(literal: String) {
        require(raw.startsWith(literal, index)) { "Expected '$literal' at index $index" }
        index += literal.length
    }

    private fun expect(expected: Char) {
        skipWhitespace()
        require(index < raw.length && raw[index] == expected) {
            "Expected '$expected' at index $index"
        }
        index++
    }

    private fun tryConsume(expected: Char): Boolean {
        skipWhitespace()
        if (index >= raw.length || raw[index] != expected) return false
        index++
        return true
    }

    private fun skipWhitespace() {
        while (index < raw.length && raw[index].isWhitespace()) index++
    }
}
