package eu.kanade.tachiyomi.ui.player.proxy

import android.content.Context
import eu.kanade.tachiyomi.ui.player.cache.CacheData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.net.URLEncoder

class ProxyServer(
    private val context: Context,
) {

    private val baseUrl = "http://127.0.0.1:8080"
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    private var cacheData: CacheData? = null

    fun started(): Boolean {
        return server != null
    }
    suspend fun start() {
        stop()

        server = embeddedServer(CIO, port = 8080) {
            routing {
                get("/chunk/{segmentIdx}") {
                    if (cacheData == null)return@get
                    if (cacheData !is CacheData.HLSStream)return@get
                    val streamChunks = (cacheData as CacheData.HLSStream).streamChunks
                    val segmentIdx = call.parameters["segmentIdx"]?.toIntOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val chunk = streamChunks.find { it.segment.idx == segmentIdx }
                    logcat { "Serving Proxy request for segment $segmentIdx" }
                    if (chunk != null && chunk.chunkFile.exists()) {
                        call.response.header(HttpHeaders.ContentType, "video/mp2t")
                        call.respondFile(chunk.chunkFile)
                    } else {
                        val redirect = chunk?.segment?.originalUrl
                        if (redirect != null) {
                            call.respondRedirect(redirect)
                        } else {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    }
                }
                get("/direct_video.mp4") {
                    if (cacheData == null) return@get
                    if (cacheData !is CacheData.DirectVideo)return@get
                    val directVideoFile = (cacheData as CacheData.DirectVideo).file
                    val orgUrl = call.request.queryParameters["orgUrl"]
                    if (directVideoFile.exists()) {
                        call.respondFile(directVideoFile)
                    } else if (orgUrl != null) {
                        call.respondRedirect(orgUrl)
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
                get("/stream_file") {
                    val filePath = call.request.queryParameters["filePath"]
                    if (filePath != null) {
                        val file = File(filePath)
                        if (file.exists()) {
                            call.respondFile(file)
                        } else {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    } else {
                        call.respond(HttpStatusCode.BadRequest)
                    }
                }
            }
        }
        server?.start(wait = false)
    }

    fun updateCache(cacheData: CacheData) {
        this.cacheData = cacheData
    }
    fun stop() {
        try {
            server?.engine?.stop(0, 0)
            server?.stop(0, 2_000)
        } catch (e: Exception) {
            logcat { "ProxyServer stop error: ${e.message}" }
        } finally {
            server = null
        }
    }

    fun generateProxyChunkUrl(segmentIdx: Int): String {
        return "$baseUrl/chunk/$segmentIdx"
    }

    fun getStreamFileUrl(absolutePath: String): String {
        val encodedPath = URLEncoder.encode(absolutePath, "UTF-8")
        return "$baseUrl/stream_file?filePath=$encodedPath"
    }

    fun generateProxyDirectUrl(orgUrl: String): String {
        val encodedUrl = URLEncoder.encode(orgUrl, "UTF-8")
        return "$baseUrl/direct_video.mp4?orgUrl=$encodedUrl"
    }
}
