package io.carmo.airplay.receiver

import android.graphics.Bitmap

data class AirPlayMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val progressStartMs: Long? = null,
    val progressCurrentMs: Long? = null,
    val progressEndMs: Long? = null,
    val senderId: String? = null,
    val senderName: String? = null
) {
    val hasTrackText: Boolean
        get() = !title.isNullOrBlank() || !artist.isNullOrBlank() || !album.isNullOrBlank()

    fun mergeWith(update: AirPlayMetadata): AirPlayMetadata {
        return AirPlayMetadata(
            title = update.title ?: title,
            artist = update.artist ?: artist,
            album = update.album ?: album,
            genre = update.genre ?: genre,
            progressStartMs = update.progressStartMs ?: progressStartMs,
            progressCurrentMs = update.progressCurrentMs ?: progressCurrentMs,
            progressEndMs = update.progressEndMs ?: progressEndMs,
            senderId = update.senderId ?: senderId,
            senderName = update.senderName ?: senderName
        )
    }
}

data class AudioNowPlaying(
    val metadata: AirPlayMetadata = AirPlayMetadata(),
    val coverArt: Bitmap? = null
)
