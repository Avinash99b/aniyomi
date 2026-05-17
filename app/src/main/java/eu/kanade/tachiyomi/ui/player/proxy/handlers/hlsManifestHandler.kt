package eu.kanade.tachiyomi.ui.player.proxy.handlers

import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.hlsManifestHandler(
    manifestProvider: suspend () -> String,
) {

    get("/master.m3u8") {

        val manifest = manifestProvider()

        call.respondText(
            text = manifest,
            contentType = io.ktor.http.ContentType.parse("application/vnd.apple.mpegurl"),
        )
    }
}
