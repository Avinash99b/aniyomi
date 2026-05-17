package eu.kanade.tachiyomi.ui.player.cache

import eu.kanade.tachiyomi.animesource.model.Video
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.http.Url
import io.ktor.http.toURI
import java.io.File
import java.util.UUID

data class Segment(
    val idx: Int,
    val name: String,
    val duration: Double,
    val originalUrl: String,
    var currentUrl: String,
)

class HLSStreamManager(
    private val cacheDir: File,
    private val client: HttpClient,
) {

    private val segments = mutableListOf<Segment>()

    /*
     * PRESERVE ORIGINAL PLAYLIST STRUCTURE.
     * HLS is positional.
     */
    private val playlistLines = mutableListOf<String>()

    /*
     * segmentIdx -> playlist line index
     */
    private val segmentLineIndices =
        mutableMapOf<Int, Int>()

    private lateinit var originalPlaylistContent: String

    private lateinit var finalPlaylistUrl: String

    private lateinit var originalUrl: String

    suspend fun init(
        video: Video,
    ): List<Segment> {
        originalUrl = video.videoUrl

        segments.clear()
        playlistLines.clear()
        segmentLineIndices.clear()

        finalPlaylistUrl = resolveMediaPlaylist(video)

        originalPlaylistContent = client
            .get(finalPlaylistUrl) {
                video.headers?.forEach { (header, value) ->
                    headers.append(header, value)
                }
            }
            .body()

        val baseUrl = Url(finalPlaylistUrl)

        val lines = originalPlaylistContent.lines()

        var currentDuration = 0.0
        var segmentIdx = 0
        var expectingSegmentUrl = false

        lines.forEach { rawLine ->

            val line = rawLine.trim()

            playlistLines += line

            if (line.isBlank()) {
                return@forEach
            }

            when {
                line.startsWith("#EXTINF:") -> {
                    currentDuration = line
                        .substringAfter("#EXTINF:")
                        .substringBefore(",")
                        .toDoubleOrNull()
                        ?: 0.0

                    expectingSegmentUrl = true
                }

                /*
                 * ONLY segment lines directly after EXTINF.
                 */
                expectingSegmentUrl &&
                    !line.startsWith("#") -> {
                    val absoluteUrl = resolveUrl(
                        baseUrl = baseUrl,
                        path = line,
                    )

                    val cleanUrl = absoluteUrl
                        .substringBefore("#")

                    val name = cleanUrl
                        .substringAfterLast("/")
                        .substringBefore("?")
                        .ifBlank {
                            "segment_$segmentIdx"
                        }

                    segments += Segment(
                        idx = segmentIdx,
                        name = name,
                        duration = currentDuration,
                        originalUrl = absoluteUrl,
                        currentUrl = absoluteUrl,
                    )

                    /*
                     * Store EXACT playlist line index.
                     */
                    segmentLineIndices[segmentIdx] =
                        playlistLines.lastIndex

                    segmentIdx++

                    expectingSegmentUrl = false
                }
            }
        }

        return segments.toList()
    }

    fun editSegment(
        idx: Int,
        newUrl: String,
    ) {
        segments
            .find { it.idx == idx }
            ?.currentUrl = newUrl
    }

    fun getOriginalUrl(): String {
        return originalUrl
    }

    fun generateStreamFile(): File {
        cacheDir.mkdirs()

        val playlistFile = File(
            cacheDir,
            "hls_${UUID.randomUUID()}.m3u8",
        )

        return try {
            if (segments.isEmpty()) {
                fallbackOriginalPlaylist(playlistFile)
            } else {
                /*
                 * Clone ORIGINAL playlist structure.
                 */
                val generatedLines =
                    playlistLines.toMutableList()

                /*
                 * Replace ONLY segment URL lines.
                 */
                segments.forEach { segment ->

                    val lineIdx =
                        segmentLineIndices[segment.idx]
                            ?: return@forEach

                    generatedLines[lineIdx] =
                        segment.currentUrl
                }

                playlistFile.writeText(
                    generatedLines.joinToString("\n"),
                )

                playlistFile
            }
        } catch (_: Exception) {
            fallbackOriginalPlaylist(playlistFile)
        }
    }

    private suspend fun resolveMediaPlaylist(
        video: Video,
    ): String {
        val playlistUrl = video.videoUrl

        val content: String = client
            .get(playlistUrl) {
                video.headers?.forEach { (name, value) ->
                    headers.append(name, value)
                }
            }
            .body()

        val lines = content
            .lines()
            .map { it.trim() }

        val isMasterPlaylist = lines.any {
            it.startsWith("#EXT-X-STREAM-INF")
        }

        if (!isMasterPlaylist) {
            return playlistUrl
        }

        val baseUrl = Url(playlistUrl)

        val variantPath = lines.firstOrNull {
            it.isNotEmpty() &&
                !it.startsWith("#")
        } ?: return playlistUrl

        return resolveUrl(
            baseUrl = baseUrl,
            path = variantPath,
        )
    }

    private fun resolveUrl(
        baseUrl: Url,
        path: String,
    ): String {
        return if (
            path.startsWith("http://") ||
            path.startsWith("https://")
        ) {
            path
        } else {
            baseUrl
                .toURI()
                .resolve(path)
                .toString()
        }
    }

    private fun fallbackOriginalPlaylist(
        file: File,
    ): File {
        file.writeText(originalPlaylistContent)

        return file
    }
}
