package com.cardvault.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cards",
    indices = [Index(value = ["number"], unique = true)],
)
data class CardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 持卡人姓名（卡面 CARDHOLDER） */
    val cardholder: String,
    /** 纯数字卡号；全库唯一 */
    val number: String,
    val expiryMonth: Int? = null,
    /** 四位年份，如 2027 */
    val expiryYear: Int? = null,
    val cvv: String? = null,
    /** CardBrand.name */
    val brand: String,
    /** 发卡行显示名（可手动编辑） */
    val bankName: String = "",
    /** BankDirectory 银行代码，用于默认卡面样式 */
    val bankCode: String? = null,
    /** 卡类型：debit / credit / prepaid；null = 未知 */
    val cardType: String? = null,
    /** 备注/别名 */
    val alias: String = "",
    /** 卡面样式 id；null = 自动 */
    val styleId: String? = null,
    /** 归档后不在卡片墙默认视图显示 */
    val archived: Boolean = false,
    /** 用户自定义排序；数值越小越靠前 */
    val orderPosition: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 卡类型取值与显示名 */
enum class CardKind(val key: String, val label: String) {
    DEBIT("debit", "借记卡"),
    CREDIT("credit", "信用卡"),
    PREPAID("prepaid", "预付卡");

    companion object {
        fun fromKey(key: String?): CardKind? =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
    }
}
