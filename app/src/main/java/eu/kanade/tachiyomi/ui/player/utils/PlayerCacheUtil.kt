package eu.kanade.tachiyomi.ui.player.utils

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.serialize
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.toHosterList
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.track.anime.model.AnimeTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object PlayerCacheUtil {

    private val cacheUtilPrefsKey = "PlayerCacheUtil"

    fun sharedPrefs(): SharedPreferences = Injekt.get<Application>().getSharedPreferences(
        cacheUtilPrefsKey,
        Context.MODE_PRIVATE,
    )
    fun cacheAnime(anime: Anime) {
        val animeString = Json.encodeToString(Anime.serializer(), anime)

        sharedPrefs().edit(commit = true) {
            putString("anime_${anime.id}", animeString)
        }
    }

    fun cacheTracks(animeId: Long, tracks: List<AnimeTrack>) {
        val tracksString = Json.encodeToString(ListSerializer(AnimeTrack.serializer()), tracks)
        sharedPrefs().edit(commit = true) {
            putString("tracks_$animeId", tracksString)
        }
    }

    fun findCachedTracks(animeId: Long): List<AnimeTrack>? {
        val tracksString = sharedPrefs().getString("tracks_$animeId", null)
        return if (tracksString != null) {
            logcat { "Using cached tracks $tracksString" }

            Json.decodeFromString(ListSerializer(AnimeTrack.serializer()), tracksString)
        } else {
            null
        }
    }
    fun findCachedAnime(animeId: Long): Anime? {
        val animeString = sharedPrefs().getString("anime_$animeId", null)
        return if (animeString != null) {
            logcat { "Using cached anime $animeString" }

            Json.decodeFromString(Anime.serializer(), animeString)
        } else {
            null
        }
    }

    fun cacheHosters(animeId: Long, episodeId: Long, hosters: List<Hoster>) {
        val hostersString = hosters.serialize()
        sharedPrefs().edit(commit = true) {
            putString("hosters_$animeId\\_$episodeId", hostersString)
        }
    }

    fun findCachedHosters(animeId: Long, episodeId: Long): List<Hoster>? {
        val hostersString = sharedPrefs().getString("hosters_$animeId\\_$episodeId", null)
        return if (hostersString != null) {
            logcat { "Using cached hosters $hostersString" }
            hostersString.toHosterList()
        } else {
            null
        }
    }

    fun clearCache(context: Context, animeId: Long) {
        sharedPrefs().edit {
            remove("anime_$animeId")
            remove("tracks_$animeId")
        }
    }
}
