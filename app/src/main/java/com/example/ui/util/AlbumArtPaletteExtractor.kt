package com.example.ui.util

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.ui.theme.LosslessCyan
import com.example.ui.theme.StudioAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ExtractedAlbumColors(
    val primaryAccent: Color = StudioAmber,
    val secondaryGlow: Color = LosslessCyan
)

class AlbumArtPaletteExtractor(
    private val context: Context,
    private val imageLoader: ImageLoader
) {
    private val cache = mutableMapOf<String, ExtractedAlbumColors>()

    suspend fun extractColors(artUrl: String?): ExtractedAlbumColors {
        if (artUrl.isNullOrBlank()) return ExtractedAlbumColors()
        cache[artUrl]?.let { return it }

        return withContext(Dispatchers.IO) {
            val result = runCatching {
                val request = ImageRequest.Builder(context)
                    .data(artUrl)
                    .allowHardware(false)
                    .size(160)
                    .build()

                val outcome = imageLoader.execute(request)
                if (outcome is SuccessResult) {
                    val bitmap = (outcome.drawable as? BitmapDrawable)?.bitmap
                    if (bitmap != null) {
                        val palette = Palette.from(bitmap).generate()
                        val vibrant = palette.lightVibrantSwatch?.rgb
                            ?: palette.vibrantSwatch?.rgb
                            ?: palette.dominantSwatch?.rgb
                        val secondary = palette.mutedSwatch?.rgb
                            ?: palette.darkVibrantSwatch?.rgb
                            ?: palette.lightMutedSwatch?.rgb

                        val primaryColor = vibrant?.let { ensureDarkBackgroundContrast(Color(it)) }
                            ?: StudioAmber
                        val glowColor = secondary?.let { ensureDarkBackgroundContrast(Color(it)) }
                            ?: LosslessCyan

                        ExtractedAlbumColors(
                            primaryAccent = primaryColor,
                            secondaryGlow = glowColor
                        )
                    } else {
                        ExtractedAlbumColors()
                    }
                } else {
                    ExtractedAlbumColors()
                }
            }.getOrDefault(ExtractedAlbumColors())

            cache[artUrl] = result
            result
        }
    }

    private fun ensureDarkBackgroundContrast(color: Color): Color {
        // Lift very dark swatches so accents pop cleanly against #0B0D12 obsidian
        val lum = color.luminance()
        return if (lum < 0.22f) {
            Color(
                red = (color.red + 0.35f).coerceIn(0f, 1f),
                green = (color.green + 0.35f).coerceIn(0f, 1f),
                blue = (color.blue + 0.35f).coerceIn(0f, 1f),
                alpha = 1f
            )
        } else {
            color
        }
    }
}
