package com.cardvault.app.ui.edit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cardvault.app.data.CardEntity
import com.cardvault.app.data.CardKind
import com.cardvault.app.data.CardRepository
import com.cardvault.app.data.DuplicateCardNumberException
import com.cardvault.app.data.SettingsRepository
import com.cardvault.app.domain.BankDirectory
import com.cardvault.app.domain.CardBrand
import com.cardvault.app.nfc.NfcCardDraft
import com.cardvault.app.network.BinLookupService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed interface LookupState {
    data object Idle : LookupState
    data object Loading : LookupState
    data class Done(val message: String) : LookupState
    data class Error(val message: String) : LookupState
}

class EditCardViewModel(
    private val repo: CardRepository,
    private val binService: BinLookupService,
    private val settingsRepo: SettingsRepository,
    private val cardId: Long,
    initialDraft: NfcCardDraft? = null,
) : ViewModel() {

    var loaded by mutableStateOf(cardId <= 0)
        private set

    var cardholder by mutableStateOf("")
    var number by mutableStateOf("")
        private set
    var expiryRaw by mutableStateOf("") // MMYY
    var cvv by mutableStateOf("")
    var bankName by mutableStateOf("")
        private set
    var bankCode by mutableStateOf<String?>(null)
        private set
    var alias by mutableStateOf("")
    var styleId by mutableStateOf<String?>(null)
    /** 卡类型（借记/信用/预付）；在线 BIN 查询命中时自动回填，也可手动指定 */
    var cardType by mutableStateOf<String?>(null)
    private var archived = false
    private var orderPosition by mutableStateOf(0L)

    /** 手动指定卡组织；null = 按卡号自动识别 */
    var brandOverride by mutableStateOf<CardBrand?>(null)

    /** 用户手动改过发卡行后不再自动覆盖 */
    private var bankEdited = false

    var lookupState by mutableStateOf<LookupState>(LookupState.Idle)
        private set

    val detectedBrand: CardBrand get() = CardBrand.detect(number)
    val brand: CardBrand get() = brandOverride ?: detectedBrand

    init {
        if (cardId > 0) viewModelScope.launch {
            repo.getById(cardId)?.let { c ->
                cardholder = c.cardholder
                number = c.number
                expiryRaw = if (c.expiryMonth != null && c.expiryYear != null)
                    "%02d%02d".format(c.expiryMonth, c.expiryYear % 100) else ""
                cvv = c.cvv.orEmpty()
                bankName = c.bankName
                bankCode = c.bankCode
                alias = c.alias
                styleId = c.styleId
                cardType = c.cardType
                archived = c.archived
                orderPosition = c.orderPosition
                val detected = CardBrand.detect(c.number)
                brandOverride = CardBrand.fromName(c.brand).takeIf { it != detected }
                bankEdited = c.bankName.isNotBlank() &&
                    c.bankName != BankDirectory.findBank(c.number)?.name
            }
            loaded = true
        } else if (initialDraft != null) {
            applyNfcDraft(initialDraft)
        }
    }

    private fun applyNfcDraft(draft: NfcCardDraft) {
        onNumberChange(draft.number)
        if (draft.expiryMonth != null && draft.expiryYear != null) {
            expiryRaw = "%02d%02d".format(draft.expiryMonth, draft.expiryYear % 100)
        }
    }

    fun onNumberChange(value: String) {
        number = value.filter { it in '0'..'9' }.take(19)
        if (!bankEdited) {
            val bank = BankDirectory.findBank(number)
            bankName = bank?.name.orEmpty()
            bankCode = bank?.code
        }
    }

    fun onBankNameChange(value: String) {
        bankName = value
        bankEdited = true
        // 手动输入的银行名尝试反查 code 以套用主题色
        bankCode = BankDirectory.banks.firstOrNull { it.name == value.trim() }?.code
    }

    fun verifyOnline() {
        viewModelScope.launch {
            lookupState = LookupState.Loading
            val settings = settingsRepo.settings.first()
            binService.lookup(number, settings.proxyUrl.ifBlank { null }, settings.offlineMode)
                .onSuccess { info ->
                    if (info.brand != CardBrand.UNKNOWN) brandOverride =
                        info.brand.takeIf { it != detectedBrand }
                    if (!info.bankName.isNullOrBlank() && !bankEdited) {
                        bankName = info.bankName
                    }
                    CardKind.fromKey(info.cardType)?.let { cardType = it.key }
                    val parts = listOfNotNull(
                        info.bankName,
                        info.brand.takeIf { it != CardBrand.UNKNOWN }?.displayName,
                        CardKind.fromKey(info.cardType)?.label,
                        info.country,
                    )
                    lookupState = LookupState.Done(
                        if (parts.isEmpty()) "接口未返回有效信息" else "识别结果：${parts.joinToString(" · ")}"
                    )
                }
                .onFailure {
                    lookupState = LookupState.Error(it.message ?: "查询失败")
                }
        }
    }

    val expiryMonth: Int?
        get() = expiryRaw.take(2).toIntOrNull()?.takeIf { it in 1..12 && expiryRaw.length >= 4 }

    val expiryYear: Int?
        get() = expiryRaw.drop(2).take(2).toIntOrNull()
            ?.takeIf { expiryRaw.length >= 4 }
            ?.let { yy ->
                // 20xx；如 97 之类的历史卡按 19xx 处理没有意义，统一 2000+
                2000 + yy
            }

    fun validate(): String? = when {
        cardholder.isBlank() -> "请填写持卡人姓名"
        number.length < 8 -> "卡号至少 8 位"
        expiryRaw.isNotEmpty() && expiryRaw.length < 4 -> "有效期格式为 MM/YY"
        expiryRaw.length >= 2 && expiryRaw.take(2).toIntOrNull() !in 1..12 -> "有效期月份无效"
        else -> null
    }

    fun buildPreviewCard(): CardEntity = CardEntity(
        id = if (cardId > 0) cardId else 0,
        cardholder = cardholder,
        number = number,
        expiryMonth = expiryMonth,
        expiryYear = expiryYear,
        cvv = cvv.ifBlank { null },
        brand = brand.name,
        bankName = bankName.trim(),
        bankCode = bankCode,
        cardType = cardType,
        archived = archived,
        alias = alias.trim(),
        styleId = styleId,
        orderPosition = orderPosition,
        createdAt = 0,
        updatedAt = 0,
    )

    var saving by mutableStateOf(false)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set

    /** 防重入：保存进行中忽略再次点击，避免快速双击插入两张重复卡 */
    fun save(onDone: () -> Unit) {
        if (saving || validate() != null) return
        saving = true
        saveError = null
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val existing = if (cardId > 0) repo.getById(cardId) else null
                repo.upsert(
                    buildPreviewCard().copy(
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                    )
                )
                onDone()
            } catch (e: DuplicateCardNumberException) {
                saveError = e.message
            } catch (e: Exception) {
                saveError = e.message ?: "保存失败"
            } finally {
                saving = false
            }
        }
    }

    companion object {
        /** 供有效期占位提示 */
        fun currentYearHint(): String = "%02d".format((LocalDate.now().year + 3) % 100)
    }
}
