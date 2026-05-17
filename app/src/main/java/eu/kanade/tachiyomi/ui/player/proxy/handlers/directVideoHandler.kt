package eu.kanade.tachiyomi.ui.player.proxy.handlers

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File

fun Route.directVideoHandler(
    cacheFile: File,
) {

    get("/video") {

        val rangeHeader = call.request.headers[HttpHeaders.Range]

        if (rangeHeader == null) {
            call.respondFile(cacheFile)
            return@get
        }

        val bytesRange = rangeHeader
            .removePrefix("bytes=")
            .split("-")

        val start = bytesRange[0].toLong()

        val end = if (bytesRange[1].isNotBlank()) {
            bytesRange[1].toLong()
        } else {
            cacheFile.length() - 1
        }

        val contentLength = end - start + 1

        call.response.status(HttpStatusCode.PartialContent)

        call.respondOutputStream {

            cacheFile.inputStream().use { input ->

                input.skip(start)

                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

                var remaining = contentLength

                while (remaining > 0) {

                    val read = input.read(
                        buffer,
                        0,
                        minOf(buffer.size.toLong(), remaining).toInt(),
                    )

                    if (read == -1) break

                    write(buffer, 0, read)

                    remaining -= read
                }
            }
        }
    }
}
