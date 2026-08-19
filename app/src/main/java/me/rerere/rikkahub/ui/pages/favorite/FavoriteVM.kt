package me.rerere.rikkahub.ui.pages.favorite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.favorite.NodeFavoriteAdapter
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.FavoriteType
import me.rerere.rikkahub.data.model.isActiveNow
import me.rerere.rikkahub.data.repository.FavoriteRepository
import kotlin.uuid.Uuid

data class NodeFavoriteListItem(
    val id: String,
    val refKey: String,
    val conversationId: Uuid,
    val nodeId: Uuid,
    val conversationTitle: String,
    val preview: String,
    val createdAt: Long,
)

class FavoriteVM(
    private val favoriteRepository: FavoriteRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val nodeFavorites = combine(
        favoriteRepository.listByType(FavoriteType.NODE),
        settingsStore.settingsFlow,
        tickerFlow(SUPERVISION_TICK_MS),
    ) { favorites, settings, _ ->
        val hidden = settings.supervision.let { supervision ->
            if (supervision.isActiveNow()) supervision.lockedConversationIds else emptySet()
        }
        favorites
            .filterNot { entity ->
                val conversationId = NodeFavoriteAdapter.decodeRef(entity)?.conversationId
                conversationId != null && conversationId in hidden
            }
            .mapNotNull { entity ->
                val ref = NodeFavoriteAdapter.decodeRef(entity) ?: return@mapNotNull null
                val meta = NodeFavoriteAdapter.decodeMeta(entity)

                NodeFavoriteListItem(
                    id = entity.id,
                    refKey = entity.refKey,
                    conversationId = ref.conversationId,
                    nodeId = ref.nodeId,
                    conversationTitle = meta?.title.orEmpty(),
                    preview = meta?.previewText ?: "",
                    createdAt = entity.createdAt,
                )
            }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun removeFavorite(refKey: String) {
        viewModelScope.launch {
            favoriteRepository.deleteByRefKey(refKey)
        }
    }

    suspend fun getEntityByRefKey(refKey: String): FavoriteEntity? {
        return favoriteRepository.getByRefKey(refKey)
    }

    fun restoreFavorite(entity: FavoriteEntity) {
        viewModelScope.launch {
            favoriteRepository.upsert(entity)
        }
    }
}

private const val SUPERVISION_TICK_MS = 30_000L

private fun tickerFlow(periodMs: Long) = flow {
    while (true) {
        emit(System.currentTimeMillis())
        delay(periodMs)
    }
}
