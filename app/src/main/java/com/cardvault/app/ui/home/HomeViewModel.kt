package com.cardvault.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cardvault.app.data.CardEntity
import com.cardvault.app.data.CardRepository
import com.cardvault.app.domain.CardBrand
import com.cardvault.app.domain.CardValidation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class CardFilter(val label: String) {
    ALL("全部"),
    EXPIRING("即将到期"),
    EXPIRED("已过期"),
}

class HomeViewModel(private val repo: CardRepository) : ViewModel() {

    val query = MutableStateFlow("")
    val filter = MutableStateFlow(CardFilter.ALL)

    val cards: StateFlow<List<CardEntity>> =
        combine(repo.observeAll(), query, filter) { list, q, f ->
            list.filter { matchesFilter(it, f) && matchesQuery(it, q) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val expiringCount: StateFlow<Int> = repo.observeAll()
        .map { list -> list.count { statusOf(it) == CardValidation.ExpiryStatus.EXPIRING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun delete(card: CardEntity) = viewModelScope.launch { repo.delete(card) }

    private fun statusOf(card: CardEntity) =
        CardValidation.expiryStatus(card.expiryMonth, card.expiryYear)

    private fun matchesFilter(card: CardEntity, f: CardFilter): Boolean = when (f) {
        CardFilter.ALL -> true
        CardFilter.EXPIRING -> statusOf(card) == CardValidation.ExpiryStatus.EXPIRING
        CardFilter.EXPIRED -> statusOf(card) == CardValidation.ExpiryStatus.EXPIRED
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
