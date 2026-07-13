package io.carmo.airplay.receiver

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

object AirPlayMetadataParser {
    private val textCharset: Charset = StandardCharsets.UTF_8

    fun parse(data: ByteArray): AirPlayMetadata {
        if (data.size < TAG_HEADER_BYTES) {
            return AirPlayMetadata()
        }
        val fields = mutableMapOf<String, String>()
        parseTags(data, 0, data.size, fields, depth = 0)
        return AirPlayMetadata(
            title = fields["minm"] ?: fields["©nam"],
            artist = fields["asar"] ?: fields["©ART"],
            album = fields["asal"] ?: fields["©alb"],
            genre = fields["asgn"] ?: fields["©gen"]
        )
    }

    private fun parseTags(
        data: ByteArray,
        start: Int,
        end: Int,
        fields: MutableMap<String, String>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return
        var index = start
        while (index + TAG_HEADER_BYTES <= end) {
            val tag = tagAt(data, index)
            val length = intAt(data, index + 4)
            val valueStart = index + TAG_HEADER_BYTES
            val valueEnd = valueStart + length
            if (length < 0 || valueEnd > end) {
                return
            }

            if (tag in TRACK_TEXT_TAGS) {
                readText(data, valueStart, valueEnd)?.let { fields[tag] = it }
            } else if (tag in CONTAINER_TAGS || looksLikeNestedTags(data, valueStart, valueEnd)) {
                parseTags(data, valueStart, valueEnd, fields, depth + 1)
            }
            index = valueEnd
        }
    }

    private fun looksLikeNestedTags(data: ByteArray, start: Int, end: Int): Boolean {
        if (start + TAG_HEADER_BYTES > end) return false
        val length = intAt(data, start + 4)
        return length >= 0 && start + TAG_HEADER_BYTES + length <= end &&
            tagAt(data, start).all { it.isLetterOrDigit() || it.code > 127 }
    }

    private fun readText(data: ByteArray, start: Int, end: Int): String? {
        if (start >= end) return null
        val value = String(data, start, end - start, textCharset)
            .trim { it <= ' ' || it == '\u0000' }
        return value.takeIf { it.isNotBlank() }
    }

    private fun tagAt(data: ByteArray, index: Int): String {
        return String(data, index, 4, StandardCharsets.ISO_8859_1)
    }

    private fun intAt(data: ByteArray, index: Int): Int {
        return ((data[index].toInt() and 0xFF) shl 24) or
            ((data[index + 1].toInt() and 0xFF) shl 16) or
            ((data[index + 2].toInt() and 0xFF) shl 8) or
            (data[index + 3].toInt() and 0xFF)
    }

    private val TRACK_TEXT_TAGS = setOf(
        "minm",
        "asar",
        "asal",
        "asgn",
        "©nam",
        "©ART",
        "©alb",
        "©gen"
    ).map { it.normalizeTag() }.toSet()

    private val CONTAINER_TAGS = setOf(
        "mlit",
        "mdcl",
        "mper",
        "mcon",
        "agal",
        "aply"
    )

    private fun String.normalizeTag(): String {
        return if (length == 4) this else uppercase(Locale.US)
    }

    private const val TAG_HEADER_BYTES = 8
    private const val MAX_DEPTH = 4
}
