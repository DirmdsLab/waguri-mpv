package `is`.xyz.mpv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors
import kotlin.math.max

internal class MediaThumbnailLoader(context: Context) {

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cache =
        object : LruCache<String, Bitmap>(CACHE_SIZE_KB) {
            override fun sizeOf(key: String, value: Bitmap): Int {
                return max(1, value.byteCount / 1024)
            }
        }

    fun load(mediaFile: MediaFile, callback: (Bitmap?) -> Unit) {
        val key = mediaFile.cacheKey
        val cached = cache.get(key)

        if (cached != null) {
            callback(cached)
            return
        }

        executor.execute {
            val bitmap = extractThumbnail(mediaFile)
            if (bitmap != null) {
                cache.put(key, bitmap)
            }

            mainHandler.post { callback(bitmap) }
        }
    }

    private fun extractThumbnail(mediaFile: MediaFile): Bitmap? {
        val retriever = MediaMetadataRetriever()

        return try {
            retriever.setDataSource(appContext, mediaFile.uri)

            val sourceBitmap =
                if (mediaFile.isVideo) {
                    extractVideoFrame(retriever, mediaFile)
                } else {
                    extractEmbeddedArtwork(retriever)
                }

            scaleBitmap(sourceBitmap)
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // Ignore retriever cleanup failures.
            }
        }
    }

    private fun extractVideoFrame(
        retriever: MediaMetadataRetriever,
        mediaFile: MediaFile,
    ): Bitmap? {
        val durationMs =
            mediaFile.durationMs
                ?: retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()

        val timestampUs =
            if (durationMs != null && durationMs > 2_000L) {
                val seed = mediaFile.uri.toString().hashCode().toLong() and 0x7fffffffL
                val positionRatio = 0.18 + (seed % 6_400L) / 10_000.0
                (durationMs * 1_000.0 * positionRatio).toLong()
            } else {
                -1L
            }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (timestampUs < 0L) {
                retriever.getScaledFrameAtTime(
                    -1L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    TARGET_WIDTH,
                    TARGET_HEIGHT,
                )
            } else {
                retriever.getScaledFrameAtTime(
                    timestampUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    TARGET_WIDTH,
                    TARGET_HEIGHT,
                )
            }
        } else {
            if (timestampUs < 0L) {
                retriever.getFrameAtTime()
            } else {
                retriever.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        }
    }

    private fun extractEmbeddedArtwork(retriever: MediaMetadataRetriever): Bitmap? {
        val picture = retriever.embeddedPicture ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(picture, 0, picture.size, bounds)
        var sample = 1
        while (
            bounds.outWidth / sample > TARGET_WIDTH * 2 ||
                bounds.outHeight / sample > TARGET_HEIGHT * 2
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(picture, 0, picture.size, options)
    }

    private fun scaleBitmap(source: Bitmap?): Bitmap? {
        source ?: return null

        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return source

        val scale = minOf(1.0, TARGET_WIDTH.toDouble() / width, TARGET_HEIGHT.toDouble() / height)

        if (scale >= 1.0) return source

        val targetWidth = max(1, (width * scale).toInt())
        val targetHeight = max(1, (height * scale).toInt())

        return try {
            Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true).also {
                if (it !== source && !source.isRecycled) {
                    source.recycle()
                }
            }
        } catch (_: Exception) {
            source
        }
    }

    fun close() {
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        cache.evictAll()
    }

    companion object {
        private const val CACHE_SIZE_KB = 16 * 1024
        private const val TARGET_WIDTH = 480
        private const val TARGET_HEIGHT = 320
    }
}
