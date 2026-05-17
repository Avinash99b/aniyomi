package eu.kanade.tachiyomi.ui.player.cache

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

object HLSCacher {
    private val client = OkHttpClient()

    fun cacheSegmentToFile(
        headers: Headers?,
        segment: Segment,
        cacheDir: File,
    ): File {
        val request = Request.Builder()
            .url(segment.originalUrl)
            .apply {
                if (headers != null) {
                    headers(headers)
                }
            }
            .build()

        client.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {
                throw IOException(
                    "Failed to download segment ${segment.idx}: ${response.code}",
                )
            }

            val body = response.body

            val tempFile = File(
                cacheDir,
                "segment_${segment.idx}.tmp",
            )

            body.byteStream().buffered().use { input ->
                tempFile.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }

            val finalFile = File(
                cacheDir,
                "segment_${segment.idx}.ts",
            )

            if (!tempFile.renameTo(finalFile)) {
                tempFile.delete()
                throw IOException("Failed renaming segment file")
            }

            return finalFile
        }
    }
}
