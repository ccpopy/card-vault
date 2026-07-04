package com.cardvault.app.nfc

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NfcCardDraft(
    val number: String,
    val expiryMonth: Int? = null,
    val expiryYear: Int? = null,
    val cardholder: String? = null,
)

/** 待导入的读卡结果：带单调时间戳，锁屏期间缓冲、解锁后消费，过旧则丢弃 */
data class PendingNfcCard(
    val draft: NfcCardDraft,
    val atElapsedMs: Long = SystemClock.elapsedRealtime(),
) {
    fun isFresh(maxAgeMs: Long = 2 * 60_000L): Boolean =
        SystemClock.elapsedRealtime() - atElapsedMs <= maxAgeMs
}

/**
 * NFC 读卡结果的中转站。
 * 用 StateFlow 而不是无 replay 的 SharedFlow：应用锁定时 UI 侧没有收集者，
 * SharedFlow 会把读卡事件静默丢掉（用户以为 NFC 坏了）；StateFlow 会把结果
 * 保留到解锁后由导航层消费。
 */
class NfcImportController {
    private val _pending = MutableStateFlow<PendingNfcCard?>(null)
    val pending: StateFlow<PendingNfcCard?> = _pending.asStateFlow()

    fun post(draft: NfcCardDraft) {
        _pending.value = PendingNfcCard(draft)
    }

    /** 消费指定事件（CAS 防止把后到的新读卡结果误清掉） */
    fun consume(event: PendingNfcCard) {
        _pending.compareAndSet(event, null)
    }
}
