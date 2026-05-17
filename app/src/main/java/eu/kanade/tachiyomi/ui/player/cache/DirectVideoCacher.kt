package eu.kanade.tachiyomi.ui.player.cache

import eu.kanade.tachiyomi.animesource.model.Video
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.copyTo
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

object DirectVideoCacher {
    val httpClient = HttpClient()

    suspend fun cacheVideo(video: Video, cacheFile: File, keepCachingEpisode: MutableStateFlow<Boolean>) {
        coroutineScope {
            val job = launch {
                val response = httpClient.get(video.videoUrl)
                val source = response.bodyAsChannel()
                source.copyTo(cacheFile.writeChannel())
            }

            keepCachingEpisode.collect { keepCaching ->
                if (!keepCaching) {
                    job.cancel()
                }
            }
        }
    }
}
