package com.cardvault.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cardvault.app.data.AppSettings
import com.cardvault.app.data.CardEntity
import com.cardvault.app.data.CardRepository
import com.cardvault.app.data.CardSortMode
import com.cardvault.app.data.SettingsRepository
import com.cardvault.app.domain.CardBrand
import com.cardvault.app.domain.CardValidation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class CardFilter(val label: String) {
    ALL("全部"),
    EXPIRING("即将到期"),
    EXPIRED("已过期"),
    ARCHIVED("已归档"),
}

data class HomeUiState(
    /** 数据库首个快照到达前为 true——避免冷启动闪现「还没有卡片」空态 */
    val loading: Boolean = true,
    val cards: List<CardEntity> = emptyList(),
    val expiringCount: Int = 0,
    val archivedCount: Int = 0,
    val sortMode: CardSortMode = CardSortMode.MANUAL,
    val noticeDays: Int = 30,
)

class HomeViewModel(
    private val repo: CardRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    val query = MutableStateFlow("")
    val filter = MutableStateFlow(CardFilter.ALL)

    // 单一上游：observeAll 只订阅一次，null 表示尚未收到首个快照（加载中）
    private val allCards: StateFlow<List<CardEntity>?> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val uiState: StateFlow<HomeUiState> =
        combine(allCards, query, filter, settings) { list, q, f, s ->
            if (list == null) return@combine HomeUiState(loading = true, sortMode = s.sortMode, noticeDays = s.expiryNoticeDays)
            val visible = list
                .filter { matchesFilter(it, f, s.expiryNoticeDays) && matchesQuery(it, q) }
                .let { sort(it, s.sortMode) }
            HomeUiState(
                loading = false,
                cards = visible,
                expiringCount = list.count {
                    !it.archived && statusOf(it, s.expiryNoticeDays) == CardValidation.ExpiryStatus.EXPIRING
                },
                archivedCount = list.count { it.archived },
                sortMode = s.sortMode,
                noticeDays = s.expiryNoticeDays,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    fun setSortMode(mode: CardSortMode) = viewModelScope.launch { settingsRepo.setSortMode(mode) }

    fun delete(card: CardEntity) = viewModelScope.launch { repo.delete(card) }

    /** 撤销删除 */
    fun restore(card: CardEntity) = viewModelScope.launch { repo.restore(card) }

    fun setArchived(card: CardEntity, archived: Boolean) =
        viewModelScope.launch { repo.setArchived(card, archived) }

    fun reorder(orderedIds: List<Long>) = viewModelScope.launch { repo.reorder(orderedIds) }

    private fun sort(cards: List<CardEntity>, mode: CardSortMode): List<CardEntity> = when (mode) {
        // 手动模式沿用数据库里的 orderPosition（DAO 已排序）
        CardSortMode.MANUAL -> cards
        CardSortMode.CREATED -> cards.sortedByDescending { it.createdAt }
        CardSortMode.BANK -> cards.sortedWith(
            compareBy({ it.bankName.isBlank() }, { it.bankName }, { it.orderPosition })
        )
        CardSortMode.EXPIRY -> cards.sortedWith(
            compareBy<CardEntity> { it.expiryYear == null || it.expiryMonth == null }
                .thenBy { (it.expiryYear ?: 9999) * 100 + (it.expiryMonth ?: 12) }
                .thenBy { it.orderPosition }
        )
    }

    private fun statusOf(card: CardEntity, noticeDays: Int) =
        CardValidation.expiryStatus(card.expiryMonth, card.expiryYear, noticeDays)

    private fun matchesFilter(card: CardEntity, f: CardFilter, noticeDays: Int): Boolean = when (f) {
        CardFilter.ALL -> !card.archived
        CardFilter.EXPIRING -> !card.archived &&
            statusOf(card, noticeDays) == CardValidation.ExpiryStatus.EXPIRING
        CardFilter.EXPIRED -> !card.archived &&
            statusOf(card, noticeDays) == CardValidation.ExpiryStatus.EXPIRED
        CardFilter.ARCHIVED -> card.archived
    }

    private fun matchesQuery(card: CardEntity, q: String): Boolean {
        if (q.isBlank()) return true
        val brand = CardBrand.fromName(card.brand)
        val fields = listOf(
            card.cardholder,
            card.bankName,
            card.alias,
            brand.displayName,
            brand.name,
            card.number,
            card.number.takeLast(4),
        )
        return fields.any { CardValidation.fuzzyMatch(it, q) }
    }
}
