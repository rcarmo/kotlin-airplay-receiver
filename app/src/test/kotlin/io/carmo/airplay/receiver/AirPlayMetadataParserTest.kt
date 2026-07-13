package io.carmo.airplay.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.StandardCharsets

class AirPlayMetadataParserTest {
    @Test
    fun parsesFlatTrackTags() {
        val data = tags(
            tag("minm", "Track Title"),
            tag("asar", "Artist Name"),
            tag("asal", "Album Name"),
            tag("asgn", "Genre Name")
        )

        val metadata = AirPlayMetadataParser.parse(data)

        assertEquals("Track Title", metadata.title)
        assertEquals("Artist Name", metadata.artist)
        assertEquals("Album Name", metadata.album)
        assertEquals("Genre Name", metadata.genre)
    }

    @Test
    fun parsesNestedLegacyTrackTags() {
        val data = tags(
            tag(
                "mlit",
                tags(
                    tag("\u00a9nam", "Legacy Title"),
                    tag("\u00a9ART", "Legacy Artist"),
                    tag("\u00a9alb", "Legacy Album")
                )
            )
        )

        val metadata = AirPlayMetadataParser.parse(data)

        assertEquals("Legacy Title", metadata.title)
        assertEquals("Legacy Artist", metadata.artist)
        assertEquals("Legacy Album", metadata.album)
    }

    @Test
    fun ignoresMalformedLength() {
        val data = "minm".toByteArray(StandardCharsets.ISO_8859_1) +
            byteArrayOf(0, 0, 0, 20) +
            "short".toByteArray(StandardCharsets.UTF_8)

        val metadata = AirPlayMetadataParser.parse(data)

        assertNull(metadata.title)
        assertNull(metadata.artist)
        assertNull(metadata.album)
    }

    private fun tags(vararg values: ByteArray): ByteArray {
        return values.fold(ByteArray(0)) { acc, value -> acc + value }
    }

    private fun tag(name: String, value: String): ByteArray {
        return tag(name, value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun tag(name: String, value: ByteArray): ByteArray {
        return name.toByteArray(StandardCharsets.ISO_8859_1) + intBytes(value.size) + value
    }

    private fun intBytes(value: Int): ByteArray {
        return byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }
}
