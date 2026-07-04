package com.cardvault.app.data

import androidx.room.withTransaction
import com.cardvault.app.domain.CardValidation
import kotlinx.coroutines.flow.Flow

data class ImportSummary(
    val total: Int,
    val inserted: Int,
    val updated: Int,
)

/** 卡号已存在（卡号全库唯一）；UI 捕获后提示用户而不是静默覆盖 */
class DuplicateCardNumberException(val existingId: Long) :
    IllegalStateException("该卡号已存在，同一张卡请直接编辑")

class CardRepository(private val db: CardDatabase) {
    private val dao = db.cardDao()

    fun observeAll(): Flow<List<CardEntity>> = dao.observeAll()
    suspend fun getAll(): List<CardEntity> = dao.getAll()
    suspend fun getById(id: Long): CardEntity? = dao.getById(id)

    /**
     * 新增置顶、编辑原位保存。写入前查重：同卡号已存在其他记录时抛
     * [DuplicateCardNumberException]，避免依赖唯一索引的裸约束错误。
     */
    suspend fun upsert(card: CardEntity): Long = db.withTransaction {
        val clashing = dao.getByNumber(card.number)
        if (clashing != null && clashing.id != card.id) {
            throw DuplicateCardNumberException(clashing.id)
        }
        if (card.id == 0L) {
            dao.insert(card.copy(orderPosition = (dao.minOrderPosition() ?: 0L) - 1L))
        } else {
            dao.update(card)
            card.id
        }
    }

    /**
     * 备份导入：整体单事务（中途失败全回滚）。
     * - 已存在的卡按卡号更新内容，但保留本机的排序位置与创建时间；
     * - 新卡按文件内先后追加到列表末尾；
     * - 文件内同卡号出现多次时仅取第一条；
     * - 卡号统一清洗为 ASCII 数字，有效期做归一化校验。
     */
    suspend fun importCards(cards: List<CardEntity>): ImportSummary = db.withTransaction {
        val now = System.currentTimeMillis()
        var inserted = 0
        var updated = 0
        var nextPosition = (dao.maxOrderPosition() ?: -1L) + 1L
        val seenNumbers = mutableSetOf<String>()

        for (card in cards) {
            val number = card.number.filter { it in '0'..'9' }
            if (number.length < 8 || !seenNumbers.add(number)) continue
            val expiry = CardValidation.normalizeExpiry(card.expiryMonth, card.expiryYear)
            val normalized = card.copy(
                id = 0,
                number = number,
                expiryMonth = expiry?.first,
                expiryYear = expiry?.second,
                createdAt = card.createdAt.takeIf { it > 0 } ?: now,
                updatedAt = now,
            )
            val existing = dao.getByNumber(number)
            if (existing == null) {
                dao.insert(normalized.copy(orderPosition = nextPosition++))
                inserted++
            } else {
                dao.update(
                    normalized.copy(
                        id = existing.id,
                        orderPosition = existing.orderPosition,
                        createdAt = existing.createdAt,
                    )
                )
                updated++
            }
        }
        normalizeOrderLocked()
        ImportSummary(total = cards.size, inserted = inserted, updated = updated)
    }

    suspend fun reorder(orderedIds: List<Long>) = dao.reorderAll(orderedIds)

    suspend fun setArchived(card: CardEntity, archived: Boolean) =
        dao.setArchived(card.id, archived, System.currentTimeMillis())

    suspend fun delete(card: CardEntity) = dao.delete(card)

    /** 撤销删除：按原 id 原样恢复（含排序位置） */
    suspend fun restore(card: CardEntity) = db.withTransaction {
        if (dao.getByNumber(card.number) == null) dao.insert(card)
    }

    private suspend fun normalizeOrderLocked() {
        dao.getAll().forEachIndexed { index, card ->
            if (card.orderPosition != index.toLong()) {
                dao.updateOrderPosition(card.id, index.toLong())
            }
        }
    }
}
