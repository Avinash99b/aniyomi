package eu.kanade.tachiyomi.ui.player.proxy.handlers

import io.ktor.server.application.call
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File

fun Route.hlsSegmentHandler(
    segmentResolver: suspend (String) -> File,
) {

    get("/segment/{name}") {

        val name = call.parameters["name"]
            ?: error("Missing segment name")

        val segment = segmentResolver(name)

        call.respondFile(segment)
    }
}
