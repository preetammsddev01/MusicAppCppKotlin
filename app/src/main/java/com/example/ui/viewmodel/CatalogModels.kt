package com.example.ui.viewmodel

import com.example.data.model.TrackEntity

data class AlbumGroup(
    val name: String,
    val artist: String,
    val artUrl: String?,
    val tracks: List<TrackEntity>,
    val hasHiRes: Boolean
)

data class ArtistGroup(
    val name: String,
    val trackCount: Int,
    val albumCount: Int,
    val artUrl: String?,
    val tracks: List<TrackEntity>
)

fun List<TrackEntity>.groupIntoAlbums(): List<AlbumGroup> {
    return groupBy { it.album }
        .map { (albumName, albumTracks) ->
            AlbumGroup(
                name = albumName,
                artist = albumTracks.firstOrNull()?.artist ?: "Unknown Artist",
                artUrl = albumTracks.firstOrNull { !it.albumArtUrl.isNullOrBlank() }?.albumArtUrl,
                tracks = albumTracks.sortedBy { it.trackNumber },
                hasHiRes = albumTracks.any { it.isHiRes }
            )
        }
        .sortedBy { it.name.lowercase() }
}

fun List<TrackEntity>.groupIntoArtists(): List<ArtistGroup> {
    return groupBy { it.artist }
        .map { (artistName, artistTracks) ->
            ArtistGroup(
                name = artistName,
                trackCount = artistTracks.size,
                albumCount = artistTracks.map { it.album }.distinct().size,
                artUrl = artistTracks.firstOrNull { !it.albumArtUrl.isNullOrBlank() }?.albumArtUrl,
                tracks = artistTracks
            )
        }
        .sortedBy { it.name.lowercase() }
}
