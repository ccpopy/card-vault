package com.cardvault.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(card: CardEntity): Long

    @Update
    suspend fun update(card: CardEntity)

    @Query("UPDATE cards SET orderPosition = :orderPosition WHERE id = :id")
    suspend fun updateOrderPosition(id: Long, orderPosition: Long)

    @Delete
    suspend fun delete(card: CardEntity)
}
