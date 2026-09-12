package ir.vmessenger.core.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vmessenger_update",
)

/**
 * The last check's conclusion, flattened for storage.
 *
 * Deliberately not the domain's `AvailableUpdate`: this is an on-disk format that
 * has to survive app upgrades, so it is versioned by being ignored when it no
 * longer parses, and the domain model stays free to change.
 */
@Serializable
data class CachedOffer(
    val versionName: String,
    val releaseNotes: String,
    val publishedAtUnixMs: Long,
    val assetName: String,
    val assetUrl: String,
    val assetSizeBytes: Long,
    val checksumsUrl: String?,
    val signingUrl: String?,
)

/** Everything the check path needs, in one read. */
data class UpdateState(
    val lastCheckedAtUnixMs: Long? = null,
    /** What the last successful check found, so the throttled path has an answer of its own. */
    val offer: CachedOffer? = null,
    /** One exact version the user waved away, not a floor: anything newer is offered again. */
    val skippedVersion: String? = null,
)

/**
 * What the updater remembers between runs. An interface so the repository can be
 * exercised without a `Context`; [UpdatePreferences] is the DataStore-backed one.
 */
interface UpdateStore {
    val state: Flow<UpdateState>

    /** Debug builds only — see `GitHubReleaseApi.resolveBaseUrl`. */
    val debugBaseUrl: Flow<String?>

    /** The throttle stamp and what that call concluded are written together, always. */
    suspend fun setLastCheck(unixMs: Long, offer: CachedOffer?)

    suspend fun setSkippedVersion(versionName: String?)

    suspend fun setDebugBaseUrl(url: String?)

    suspend fun clear()
}

@Singleton
class UpdatePreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) : UpdateStore {
    private val json = Json { ignoreUnknownKeys = true }

    override val state: Flow<UpdateState> = context.updateDataStore.data.map { preferences ->
        UpdateState(
            lastCheckedAtUnixMs = preferences[KEY_LAST_CHECKED_AT],
            offer = preferences[KEY_AVAILABLE_UPDATE]?.let(::decodeOffer),
            skippedVersion = preferences[KEY_SKIPPED_VERSION],
        )
    }

    override val debugBaseUrl: Flow<String?> = context.updateDataStore.data.map { it[KEY_DEBUG_BASE_URL] }

    override suspend fun setLastCheck(unixMs: Long, offer: CachedOffer?) {
        context.updateDataStore.edit { preferences ->
            preferences[KEY_LAST_CHECKED_AT] = unixMs
            preferences.put(KEY_AVAILABLE_UPDATE, offer?.let { json.encodeToString(CachedOffer.serializer(), it) })
        }
    }

    override suspend fun setSkippedVersion(versionName: String?) {
        context.updateDataStore.edit { preferences -> preferences.put(KEY_SKIPPED_VERSION, versionName) }
    }

    override suspend fun setDebugBaseUrl(url: String?) {
        context.updateDataStore.edit { preferences -> preferences.put(KEY_DEBUG_BASE_URL, url) }
    }

    /** Forgets the throttle, the pending offer and the skipped version (secure wipe). */
    override suspend fun clear() {
        context.updateDataStore.edit { it.clear() }
    }

    /** A snapshot an older build wrote is dropped, not thrown: it is only a cache. */
    private fun decodeOffer(encoded: String): CachedOffer? =
        runCatching { json.decodeFromString(CachedOffer.serializer(), encoded) }.getOrNull()

    /** A blank value removes the entry rather than storing an empty string. */
    private fun MutablePreferences.put(key: Preferences.Key<String>, value: String?) {
        if (value.isNullOrBlank()) remove(key) else set(key, value)
    }

    companion object {
        private val KEY_LAST_CHECKED_AT = longPreferencesKey("last_checked_at_unix_ms")
        private val KEY_AVAILABLE_UPDATE = stringPreferencesKey("available_update")
        private val KEY_SKIPPED_VERSION = stringPreferencesKey("skipped_version")
        private val KEY_DEBUG_BASE_URL = stringPreferencesKey("debug_base_url")
    }
}
