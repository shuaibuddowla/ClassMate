## ClassMate 1.1.45 Release Notes

### 🎨 Batch Fund & Dues Tracker Redesign
- **Modern Unified Layout**: Designed on an 8dp grid spacing system with soft surfaces and polished touch targets.
- **Symmetric Action Buttons**: Fixed button clipping and text overflow on "Record Expense", maintaining equal 52dp heights and clean separation.
- **Single Source of Truth**: Removed duplicate counts across campaign summary chips, keeping exact contributor numbers strictly in the segmented control (`Unpaid 29 · Paid 29 · All 58`).
- **Multi-Deposit Transparency**: Clear labeling of total contribution amount with deposit count (e.g., `৳ 1,000` • `Total (2 deposits)`) for students depositing in multiple installments.
- **Relative Date Formatting**: Clean date labels (`Today`, `Yesterday`, or `18 Nov 2026`) across transactions.

### 📢 Notice Feed & Attachment Reliability
- **Centered Date Pill Badges**: Perfected vertical and horizontal centering of date section pills between notice cards, with an aligned "Today" badge directly beneath the top bar.
- **Resilient Notice Attachments**: Resolved image and PDF upload issues to Supabase storage, added live upload progress reporting during notice publishing, and ensured instant public CDN delivery.

### 🔔 Smart Notification Permissions & Rationale
- **App-Open & Resume Prompt**: Automatically requests notification permissions on app start if disabled, ensuring students never miss urgent updates.
- **Clear Rationale Bottom Sheet**: Explains the 3 critical reasons why ClassMate requests notifications:
  1. *Urgent Notices & Cancellations*
  2. *Class & Room Reminders*
  3. *Batch Fund Receipts & Dues*
- **One-Tap OS Settings Navigation**: If permissions were previously denied in Android, provides direct one-tap routing to the system App Notification Settings page.
