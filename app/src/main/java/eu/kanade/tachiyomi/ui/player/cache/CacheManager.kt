package eu.kanade.tachiyomi.ui.player.cache

import android.content.Context
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.player.proxy.ProxyServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.system.logcat
import java.io.File

// How many segments to download at the same time
private const val PARALLEL_CHUNK_COUNT = 4

data class StreamChunk(
    val chunkFile: File,
    val segment: Segment
)

sealed class CacheData {
    data class DirectVideo(val file: File) : CacheData()
    data class HLSStream(val streamChunks: List<StreamChunk>) : CacheData()
}

sealed class CacheState {
    object NoCache : CacheState()
    data class Cache(
        val video: Video,
        val animeSource: AnimeSource,
        val episodeLoadResult: PlayerViewModel.EpisodeLoadResult,
        val cache: CacheData,
    ) : CacheState()
}

data class CacheResult(
    val video: Video,
    val proxiedUrl: String,
    val animeSource: AnimeSource
)

class CacheManager(
    val context: Context,
    val proxyServer: ProxyServer
) {
    val cacheDir = File(context.cacheDir, "/streamCache").apply { mkdirs() }
    private val httpClient = HttpClient()
    private val hlsStreamManager: HLSStreamManager = HLSStreamManager(cacheDir, httpClient)
    private val cacheState = MutableStateFlow<CacheState>(CacheState.NoCache)

    private val animeSource = MutableStateFlow<AnimeSource?>(null)

    private val caching = MutableStateFlow(false)
    val directVideoCacheFile = File(cacheDir, "direct_video.mp4")


    suspend fun startCachingEpisode(
        animeSource: AnimeSource,
        episodeLoadResult: PlayerViewModel.EpisodeLoadResult,
    ): Boolean {
        proxyServer.stop()

        if (episodeLoadResult.hosterList.isNullOrEmpty()) return false
        val hoster = episodeLoadResult.hosterList.first()
        if (hoster.videoList.isNullOrEmpty()) return false

        val video: Video = (hoster.videoList as List<Video>).first()
        val url = video.videoUrl

        keepCachingEpisode.update { true }
        caching.update { true }
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
        this.animeSource.update { animeSource }

        if (url.contains(".m3u8")) {
            cacheState.update {
                CacheState.Cache(
                    video,
                    animeSource,
                    episodeLoadResult,
                    CacheData.HLSStream(emptyList()),
                )
            }
            cacheHLSStream(video)
        } else {
            cacheState.update {
                CacheState.Cache(
                    video,
                    animeSource,
                    episodeLoadResult,
                    CacheData.DirectVideo(File(cacheDir, "direct_video.mp4")),
                )
            }
            cacheDirectVideo(video)
        }

        return true
    }

    private var keepCachingEpisode = MutableStateFlow(false)

    suspend fun stopCachingEpisode(): CacheResult? {
        if(!proxyServer.started()){
            proxyServer.start()
        }
        keepCachingEpisode.update { false }
        caching.update { false }
        if (cacheState.value is CacheState.Cache) {
            proxyServer.updateCache((cacheState.value as CacheState.Cache).cache)
        }
        return when (val state = cacheState.value) {
            is CacheState.NoCache -> null
            is CacheState.Cache -> when (state.cache) {
                is CacheData.DirectVideo -> CacheResult(
                    state.video,
                    proxyServer.generateProxyDirectUrl(state.video.videoUrl),
                    this.animeSource.value!!,
                )
                is CacheData.HLSStream -> CacheResult(
                    state.video,
                    proxyServer.getStreamFileUrl(hlsStreamManager.generateStreamFile().absolutePath),
                    this.animeSource.value!!,
                )
            }
        }
    }

    fun isCaching(): Boolean = caching.value

    fun cacheHLSStream(video: Video) {
        logcat { "Starting parallel cache for HLS stream ${video.videoUrl}" }
        CoroutineScope(Dispatchers.IO).launch {
            val segments = hlsStreamManager.init(video)

            // Process segments in windows of PARALLEL_CHUNK_COUNT at a time.
            // chunked() preserves order so the playlist stays correct.
            segments.chunked(PARALLEL_CHUNK_COUNT).forEach { batch ->
                if (!keepCachingEpisode.value) return@forEach

                // Launch each segment in the batch concurrently
                val deferredChunks = batch.map { segment ->
                    async {
                        if (!keepCachingEpisode.value) return@async null
                        val file = HLSCacher.cacheSegmentToFile(video.headers, segment, cacheDir)
                        segment.currentUrl = proxyServer.generateProxyChunkUrl(segment.idx)
                        StreamChunk(file, segment)
                    }
                }

                // Wait for the whole batch, then append results in one atomic update
                val finishedChunks = deferredChunks.awaitAll().filterNotNull()
                if (finishedChunks.isNotEmpty()) {
                    cacheState.update { currentState ->
                        if (currentState is CacheState.Cache && currentState.cache is CacheData.HLSStream) {
                            currentState.copy(
                                cache = CacheData.HLSStream(
                                    currentState.cache.streamChunks + finishedChunks
                                )
                            )
                        } else {
                            currentState
                        }
                    }
                    logcat { "Cached batch of ${finishedChunks.size} segments (last idx: ${finishedChunks.last().segment.idx})" }
                }
            }
            logcat { "HLS stream caching complete for ${video.videoUrl}" }
        }.start()
    }

    fun cacheDirectVideo(video: Video) {
        logcat { "Starting cache for Direct Video ${video.videoUrl}" }
        CoroutineScope(Dispatchers.IO).launch {
            directVideoCacheFile.delete()
            directVideoCacheFile.createNewFile()
            DirectVideoCacher.cacheVideo(video, directVideoCacheFile, keepCachingEpisode)
        }.start()
    }
}
