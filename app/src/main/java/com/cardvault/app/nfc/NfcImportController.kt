package com.cardvault.app.nfc

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class NfcCardDraft(
    val number: String,
    val expiryMonth: Int? = null,
    val expiryYear: Int? = null,
)

sealed interface NfcImportEvent {
    data class CardRead(val draft: NfcCardDraft) : NfcImportEvent
    data class Error(val message: String) : NfcImportEvent
}

class NfcImportController {
    private val _events = MutableSharedFlow<NfcImportEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    suspend fun emit(event: NfcImportEvent) {
        _events.emit(event)
    }
}
