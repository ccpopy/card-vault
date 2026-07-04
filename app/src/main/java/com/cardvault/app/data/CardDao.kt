package com.cardvault.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CardDao {
    @Query("SELECT * FROM cards ORDER BY orderPosition ASC, updatedAt DESC")
    fun observeAll(): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards ORDER BY orderPosition ASC, updatedAt DESC")
    suspend fun getAll(): List<CardEntity>

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun getById(id: Long): CardEntity?

    @Query("SELECT * FROM cards WHERE number = :number LIMIT 1")
    suspend fun getByNumber(number: String): CardEntity?

    @Query("SELECT MIN(orderPosition) FROM cards")
    suspend fun minOrderPosition(): Long?

    @Query("SELECT MAX(orderPosition) FROM cards")
    suspend fun maxOrderPosition(): Long?

    /** 卡号有唯一索引：重复插入直接抛异常，由仓库层转成友好错误，绝不静默覆盖 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(card: CardEntity): Long

    @Update
    suspend fun update(card: CardEntity)

    @Query("UPDATE cards SET orderPosition = :orderPosition WHERE id = :id")
    suspend fun updateOrderPosition(id: Long, orderPosition: Long)

    @Query("UPDATE cards SET archived = :archived, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, updatedAt: Long)

    @Delete
    suspend fun delete(card: CardEntity)

    /** 批量写排序：单事务提交，Flow 只发射一次，中途被杀不落半份 */
    @Transaction
    suspend fun reorderAll(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id ->
            updateOrderPosition(id, index.toLong())
        }
    }
}
