package com.example.bleapp

// ── Enums ─────────────────────────────────────────────────────────────────────

enum class DataFormat(val label: String) {
    UINT8("uint8"), INT8("int8"),
    UINT16("uint16"), INT16("int16"),
    UINT32("uint32"), INT32("int32"),
    HEX("hex"), ASCII("ascii")
}

enum class DataSource(val label: String) {
    MANUFACTURER("Manufacturer Data"),
    RAW("Raw Advertisement Bytes"),
    SERVICE("Service Data")
}

// ── Data Classes ──────────────────────────────────────────────────────────────

data class FilterRule(val byteIndex: Int, val value: Byte)

data class ParseRule(
    val label: String,
    val byteStart: Int,
    val byteEnd: Int,
    val format: DataFormat,
    val multiplier: Double,
    val unit: String
)

data class FrameDefinition(
    val name: String,
    val filters: List<FilterRule>,
    val rules: List<ParseRule>
)

data class ParserConfig(
    val dataSource: DataSource,
    val frames: List<FrameDefinition>
)

data class ParsedValue(val label: String, val value: String, val unit: String)

data class FrameResult(
    val definition: FrameDefinition,
    var lastUpdate: String = "—",
    var parsedValues: List<ParsedValue> = emptyList(),
    var rawHex: String = "—",
    var hasData: Boolean = false
)

// ── Global Config Holder ──────────────────────────────────────────────────────

object ParserConfigHolder {
    var config: ParserConfig? = null
}

// ── Parser Engine ─────────────────────────────────────────────────────────────

object ParserEngine {

    fun matchesFilters(bytes: ByteArray, filters: List<FilterRule>): Boolean =
        filters.all { f -> f.byteIndex < bytes.size && bytes[f.byteIndex] == f.value }

    fun applyRules(bytes: ByteArray, rules: List<ParseRule>): List<ParsedValue> =
        rules.map { rule ->
            try {
                if (rule.byteEnd >= bytes.size || rule.byteStart > rule.byteEnd)
                    return@map ParsedValue(rule.label, "out of range", rule.unit)
                val value = parseValue(bytes, rule)
                ParsedValue(rule.label, value, rule.unit)
            } catch (e: Exception) {
                ParsedValue(rule.label, "error", "")
            }
        }

    private fun parseValue(bytes: ByteArray, rule: ParseRule): String {
        val s = rule.byteStart; val e = rule.byteEnd
        return when (rule.format) {
            DataFormat.UINT8  -> fmt((bytes[s].toInt() and 0xFF).toLong(), rule.multiplier)
            DataFormat.INT8   -> fmt(bytes[s].toLong(), rule.multiplier)
            DataFormat.UINT16 -> fmt(
                ((bytes[s].toInt() and 0xFF).toLong() shl 8) or (bytes[s+1].toInt() and 0xFF).toLong(),
                rule.multiplier)
            DataFormat.INT16  -> fmt(
                ((bytes[s].toInt().toLong()) shl 8) or (bytes[s+1].toInt() and 0xFF).toLong(),
                rule.multiplier)
            DataFormat.UINT32 -> fmt(
                ((bytes[s].toLong() and 0xFF) shl 24) or ((bytes[s+1].toLong() and 0xFF) shl 16) or
                ((bytes[s+2].toLong() and 0xFF) shl 8)  or  (bytes[s+3].toLong() and 0xFF),
                rule.multiplier)
            DataFormat.INT32  -> fmt(
                ((bytes[s].toInt().toLong()) shl 24) or ((bytes[s+1].toInt() and 0xFF).toLong() shl 16) or
                ((bytes[s+2].toInt() and 0xFF).toLong() shl 8) or (bytes[s+3].toInt() and 0xFF).toLong(),
                rule.multiplier)
            DataFormat.HEX    -> bytes.slice(s..e).joinToString(" ") { "%02X".format(it) }
            DataFormat.ASCII  -> String(bytes.slice(s..e).toByteArray(), Charsets.UTF_8)
        }
    }

    private fun fmt(raw: Long, multiplier: Double): String {
        return if (multiplier == 1.0) raw.toString()
        else "%.4f".format(raw * multiplier).trimEnd('0').trimEnd('.')
    }
}
