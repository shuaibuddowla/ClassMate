# Offline Batch Fund & Unpaid Reminders Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform the Batch Fund module into a zero-data-loss, offline-first notes-like system with local caching, optimistic updates, an automated background sync worker (`WorkManager`), and an automated push reminder engine for unpaid classmates in the Dues Tracker.

**Architecture:** 
1. **Cache Layer (`ClassMateBatchFundCache`)**: Centralized local cache for student roster, fund summaries, collections, transactions, and payers using SharedPreferences/JSON with atomic writes.
2. **Offline Mutation Queue (`OfflineFundQueue`) & Sync Worker (`OfflineFundSyncWorker`)**: When offline, deposits and expenses are queued locally, balance and ledger update immediately with an amber `🕒 Pending Sync` pill badge. When internet reconnects, `OfflineFundSyncWorker` uploads them to Supabase and fires a success notification to the user.
3. **Unpaid Reminder Engine**: A dedicated `btnRemindUnpaid` button in the Dues Tracker campaign summary card that prompts the CR and dispatches targeted batch/student push notifications for overdue dues via `NotificationSender`.

**Tech Stack:** Kotlin, Android WorkManager, OkHttp, Supabase PostgREST RPC, SharedPreferences JSON caching, Material 3 UI.

---

## Task 1: Create `ClassMateBatchFundCache` and `OfflineFundQueue` Models

- [ ] **Step 1.1**: Define `PendingFundTransaction` data class in `app/src/main/java/com/shuaib/classmate/data/offline/PendingFundTransaction.kt`:
  - `id: String` (UUID)
  - `batchId: String`
  - `type: String` ("inflow" or "outflow")
  - `amount: Double`
  - `title: String`
  - `studentName: String?`
  - `studentId: String?`
  - `studentProfileId: String?`
  - `collectionId: String?`
  - `notes: String?`
  - `timestamp: Long`
  - `syncStatus: String` ("pending", "syncing", "failed")
  - Serialization helpers: `toJson()`, `fromJson(JSONObject)`

- [ ] **Step 1.2**: Define `OfflineFundQueue` in `app/src/main/java/com/shuaib/classmate/data/offline/OfflineFundQueue.kt`:
  - `enqueue(context, pending)`
  - `remove(context, id)`
  - `getAll(context, batchId): List<PendingFundTransaction>`
  - `count(context, batchId): Int`

- [ ] **Step 1.3**: Define `ClassMateBatchFundCache` in `app/src/main/java/com/shuaib/classmate/data/offline/ClassMateBatchFundCache.kt`:
  - `saveRoster(context, batchId, roster: List<JSONObject>)`
  - `getRoster(context, batchId): List<JSONObject>`
  - `saveSummary(context, batchId, summary: JSONObject)`
  - `getSummary(context, batchId): JSONObject?`
  - `saveCollections(context, batchId, collections: JSONArray)`
  - `getCollections(context, batchId): JSONArray?`
  - `saveTransactions(context, batchId, transactions: JSONArray)`
  - `getTransactions(context, batchId): JSONArray?`
  - `savePayers(context, batchId, collectionId, payers: JSONArray)`
  - `getPayers(context, batchId, collectionId): JSONArray?`

---

## Task 2: Implement `OfflineFundSyncWorker` for Background Upload & Sync Notification

- [ ] **Step 2.1**: Create `OfflineFundSyncWorker` in `app/src/main/java/com/shuaib/classmate/workers/OfflineFundSyncWorker.kt`:
  - Extend `CoroutineWorker(appContext, workerParams)`
  - Fetch pending transactions from `OfflineFundQueue`
  - For each transaction:
    - If `type == "inflow"`, call `ClassMateAuthApi.rpc("record_batch_fund_deposit", ...)`
    - If `type == "outflow"`, call `ClassMateAuthApi.rpc("record_batch_fund_expense", ...)`
    - On success, remove from `OfflineFundQueue`
  - If any transactions were synced:
    - Post an Android system notification via `NotificationHelper`: "Batch Fund Synced: X offline transaction(s) recorded successfully."
    - Send a broadcast/EventBus notification so `ClassMateBatchFundActivity` refreshes if active.
  - Return `Result.success()`.

- [ ] **Step 2.2**: Register work constraint in `OfflineFundQueue.scheduleSync(context)`:
  - `NetworkType.CONNECTED` constraint with exponential backoff.

---

## Task 3: Integrate Cache-First Loading & Optimistic Offline Mutations into `ClassMateBatchFundActivity`

- [ ] **Step 3.1**: Update `loadRoster()`:
  - First read from `ClassMateBatchFundCache.getRoster(this, batchId)`. If cached, immediately set `batchRoster = cached` so dialogs have student options instantly.
  - Fetch fresh `batch_friends` in background; update memory and `ClassMateBatchFundCache.saveRoster`.

- [ ] **Step 3.2**: Update `loadData()`:
  - First read cached summary, collections, and transactions from `ClassMateBatchFundCache`.
  - Populate UI immediately (balance, metrics, collections chips, ledger) with cached data.
  - Merge pending offline transactions from `OfflineFundQueue` into the ledger with an amber `🕒 Pending Sync` pill badge.
  - Fetch fresh data from network in background; update cache and UI smoothly without layout flash.

- [ ] **Step 3.3**: Update `saveManualTransaction()` (Deposit & Expense):
  - When network is offline or fails:
    - Create `PendingFundTransaction` and call `OfflineFundQueue.enqueue(...)`.
    - Optimistically update local summary (adjust balance, inflow/outflow).
    - Optimistically insert row at top of ledger with `🕒 Pending Sync` badge.
    - Show friendly Toast: "Saved offline. Will sync to database automatically when online."
    - Schedule `OfflineFundSyncWorker`.

---

## Task 4: Add Unpaid Reminder Engine in Dues Tracker

- [ ] **Step 4.1**: Update `dialog_fund_payers_sheet.xml`:
  - In `cardCampaignSummary`, next to `btnCopyUnpaid`, add `btnRemindUnpaid`:
    - Height: 34dp, cornerRadius: 17dp
    - Icon: `@drawable/ic_notifications` (or bell icon), text: "Remind (X)"
    - Background tint: `#FEF3C7` (amber surface), text/icon tint: `#D97706` (warm amber)
    - Visibility: GONE by default; shown only if user is CR/Admin and `unpaidCount > 0`.

- [ ] **Step 4.2**: In `ClassMateBatchFundActivity.kt` (`showPayersSheet`):
  - Wire up `btnRemindUnpaid`:
    - Displays confirmation dialog with campaign name, due amount, and unpaid student count.
    - On confirm, calls `NotificationSender.sendToBatch(...)`:
      - Title: "🔔 Batch Fund Reminder: [Campaign Name]"
      - Message: "Please clear your contribution of ৳[Amount] for [Campaign Name]. Check dues in the Batch Fund tab."
      - Extra data: `mapOf("type" to "batch_fund_reminder", "campaignId" to colId)`
    - Shows feedback toast: "Reminders sent to [X] classmates."

---

## Task 5: Verification & End-to-End Testing

- [ ] **Step 5.1**: Test offline simulation (airplane mode / network disconnected):
  - Verify student roster populates in the deposit dialog without internet.
  - Verify adding a deposit and expense offline succeeds instantly, displays `🕒 Pending Sync`, and updates balance.
  - Verify app restart preserves the offline transaction and cache.
- [ ] **Step 5.2**: Test reconnection & background sync:
  - Re-enable network, trigger sync worker, and verify Supabase database insertion.
  - Verify student receives confirmation push notification.
- [ ] **Step 5.3**: Test Dues Tracker "Remind Unpaid" button:
  - Verify button styling, placement, confirmation dialog, and push dispatch.
- [ ] **Step 5.4**: Compile release build to verify zero compile or lint regressions.
