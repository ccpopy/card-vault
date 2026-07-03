package com.cardvault.app.data

import kotlinx.coroutines.flow.Flow

data class ImportSummary(
    val total: Int,
    val inserted: Int,
    val updated: Int,
)

class CardRepository(private val dao: CardDao) {
    fun observeAll(): Flow<List<CardEntity>> = dao.observeAll()
    suspend fun getAll(): List<CardEntity> = dao.getAll()
    suspend fun getById(id: Long): CardEntity? = dao.getById(id)
    suspend fun upsert(card: CardEntity): Long =
        if (card.id == 0L) {
            dao.insert(card.copy(orderPosition = nextTopOrderPosition()))
        } else {
            dao.update(card)
            card.id
        }

    suspend fun importCards(cards: List<CardEntity>): ImportSummary {
        val now = System.currentTimeMillis()
        var inserted = 0
        var updated = 0

        cards.forEachIndexed { index, card ->
            val normalized = card.copy(
                id = 0,
                number = card.number.filter { it.isDigit() },
                orderPosition = index.toLong(),
                createdAt = card.createdAt.takeIf { it > 0 } ?: now,
                updatedAt = now,
            )
            val existing = dao.getByNumber(normalized.number)
            if (existing == null) {
                dao.insert(normalized)
                inserted++
            } else {
                dao.update(
                    normalized.copy(
                        id = existing.id,
                        createdAt = existing.createdAt,
                    )
                )
                updated++
            }
        }
        normalizeOrder()
        return ImportSummary(total = cards.size, inserted = inserted, updated = updated)
    }

    suspend fun reorder(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id ->
            dao.updateOrderPosition(id, index.toLong())
        }
    }

    suspend fun delete(card: CardEntity) = dao.delete(card)

    private suspend fun nextTopOrderPosition(): Long =
        (dao.minOrderPosition() ?: 0L) - 1L

    private suspend fun normalizeOrder() {
        dao.getAll().forEachIndexed { index, card ->
            dao.updateOrderPosition(card.id, index.toLong())
        }
    }
}
