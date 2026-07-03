package com.cardvault.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CardEntity::class], version = 2, exportSchema = false)
abstract class CardDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN orderPosition INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE cards
                    SET orderPosition = (
                        SELECT COUNT(*)
                        FROM cards AS other
                        WHERE other.updatedAt > cards.updatedAt
                           OR (other.updatedAt = cards.updatedAt AND other.id < cards.id)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
