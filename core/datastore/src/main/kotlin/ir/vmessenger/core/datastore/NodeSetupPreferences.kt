package ir.vmessenger.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.nodeSetupDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vmessenger_node_setup",
)

private val NODE_SETUP_CHOICE_KEY = stringPreferencesKey("node_setup_choice")

/**
 * What the user answered when first asked to configure a node, asked before an identity exists.
 *
 * Up to 2.0.2 a third answer, "use the test nodes", seeded the built-in node. 2.2.2 removed that node,
 * and the stored `TestNodes` now reads as [NotAsked]: an install that answered it but never created an
 * identity is asked again, since it has no node. Once an identity exists the answer is not consulted.
 */
enum class NodeSetupChoice {
    NotAsked,

    /** The user added or created their own node. */
    Custom,

    /** The user chose to continue with no node for now. */
    Skipped,
}

@Singleton
class NodeSetupPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val choice: Flow<NodeSetupChoice> = context.nodeSetupDataStore.data.map { preferences ->
        when (preferences[NODE_SETUP_CHOICE_KEY]) {
            NodeSetupChoice.Custom.name -> NodeSetupChoice.Custom
            NodeSetupChoice.Skipped.name -> NodeSetupChoice.Skipped
            else -> NodeSetupChoice.NotAsked
        }
    }

    suspend fun current(): NodeSetupChoice = choice.first()

    suspend fun setChoice(choice: NodeSetupChoice) {
        context.nodeSetupDataStore.edit { preferences ->
            preferences[NODE_SETUP_CHOICE_KEY] = choice.name
        }
    }

    /** Back to unanswered (secure wipe), so a fresh identity is asked again. */
    suspend fun clear() {
        context.nodeSetupDataStore.edit { it.clear() }
    }
}
