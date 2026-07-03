package com.cardvault.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cards")
data class CardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 持卡人姓名（卡面 CARDHOLDER） */
    val cardholder: String,
    /** 纯数字卡号 */
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
    /** 备注/别名 */
    val alias: String = "",
    /** 卡面样式 id；null = 自动 */
    val styleId: String? = null,
    /** 用户自定义排序；数值越小越靠前 */
    val orderPosition: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
)
