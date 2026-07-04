package com.cardvault.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CardEntity::class], version = 3, exportSchema = true)
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

        /** v3：卡类型 + 归档标记；清理历史重复卡号（保留最近更新的一条）后为卡号建唯一索引 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN cardType TEXT")
                db.execSQL("ALTER TABLE cards ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    DELETE FROM cards WHERE EXISTS (
                        SELECT 1 FROM cards AS other
                        WHERE other.number = cards.number
                          AND (other.updatedAt > cards.updatedAt
                               OR (other.updatedAt = cards.updatedAt AND other.id > cards.id))
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_cards_number ON cards(number)")
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
