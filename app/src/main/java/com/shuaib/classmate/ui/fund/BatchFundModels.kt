package com.shuaib.classmate.ui.fund

import org.json.JSONObject

data class FundSummary(
    val currentBalance: Double = 0.0,
    val totalCollected: Double = 0.0,
    val totalSpent: Double = 0.0,
    val cashBalance: Double = 0.0,
    val mfsBalance: Double = 0.0,
    val txCount: Int = 0,
    val isCr: Boolean = false,
    val fundDescription: String = "Used for emergency, events, jersey, and other batch expenses.",
    val batchName: String = "Batch Fund",
    val university: String = "MBSTU",
    val ongoingTitle: String = "",
    val ongoingCanon: String = "",
    val ongoingCollected: Double = 0.0,
    val ongoingPaidCount: Int = 0,
    val ongoingUnpaidCount: Int = 0,
    val ongoingProgress: Double = 0.0
)

data class FundCampaignItem(
    val id: String,
    val name: String,
    val paid: Int = 0,
    val total: Int = 0,
    val collected: Double = 0.0,
    val perStudent: Double = 0.0,
    val colorHex: Long = 0xFF6366F1
)

data class FundTransactionItem(
    val id: String,
    val type: String, // "inflow" or "outflow"
    val method: String = "Cash", // "Cash", "bKash", "Nagad"
    val title: String,
    val studentName: String? = null,
    val studentId: String? = null,
    val studentProfileId: String? = null,
    val campaign: String = "Batch Fund",
    val dateStr: String? = null,
    val formattedDate: String = "",
    val createdByName: String? = null,
    val createdAt: String? = null,
    val amount: Double = 0.0,
    val isOfflinePending: Boolean = false,
    val canEdit: Boolean = false,
    val rawJson: JSONObject? = null
)

data class FundStudentDue(
    val studentId: String,
    val profileId: String?,
    val fullName: String,
    val paidMap: Map<String, Boolean> = emptyMap(),
    val totalPaid: Double = 0.0,
    val paymentCount: Int = 0
)

sealed class FundDrawerMode {
    object None : FundDrawerMode()
    object QuickCollect : FundDrawerMode()
    data class Record(
        val txType: String = "deposit", // "deposit" or "expense"
        val prefillStudentName: String? = null,
        val prefillProfileId: String? = null,
        val prefillTitle: String? = null
    ) : FundDrawerMode()
    object Analytics : FundDrawerMode()
    object Info : FundDrawerMode()
    data class Remind(val campaignName: String) : FundDrawerMode()
    object NewCampaign : FundDrawerMode()
    data class TxDetail(val tx: FundTransactionItem) : FundDrawerMode()
    data class EditTx(val tx: FundTransactionItem) : FundDrawerMode()
}

data class UndoAction(
    val message: String,
    val onUndo: () -> Unit
)
