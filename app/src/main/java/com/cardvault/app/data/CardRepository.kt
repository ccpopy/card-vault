package com.cardvault.app.data

import kotlinx.coroutines.flow.Flow

class CardRepository(private val dao: CardDao) {
    fun observeAll(): Flow<List<CardEntity>> = dao.observeAll()
    suspend fun getById(id: Long): CardEntity? = dao.getById(id)
    suspend fun upsert(card: CardEntity): Long =
        if (card.id == 0L) dao.insert(card) else { dao.update(card); card.id }
    suspend fun delete(card: CardEntity) = dao.delete(card)
}
