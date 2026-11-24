# Post Notice Jetpack Compose Migration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the notice posting flow in `ClassMatePublishActivity` from legacy imperative Android Views into modern, high-performance Jetpack Compose with a WhatsApp-style message/attachment composer and AI title auto-detection.

**Architecture:** Create modular Jetpack Compose screens (`PostNoticeScreen.kt`, `NoticeComposerBar.kt`, `NoticeAttachmentPreviews.kt`) hosted inside `ClassMatePublishActivity` when `upload == false`. Maintain 100% backend parity for Supabase RPCs, Cloudinary/Supabase attachment uploads, Gemini AI voice transcription, and OfflineNoticeQueue fallback.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Coroutines, Supabase, Cloudinary, Gemini Multimodal Audio Transcription, Hilt / AppContext.

---

## Tasks

### Task 1: Scaffolding and State Management
- [ ] Create `PostNoticeState.kt` data model representing notice types (General, Cancellation, Poll), message text, attachments (PDF, photos), voice recording state, poll options, and validation.
- [ ] Wire attachment pickers (Photo gallery launcher up to 5 items, PDF Document picker).

### Task 2: Build WhatsApp-Style Composer (`NoticeComposerBar.kt` & `NoticeAttachmentPreviews.kt`)
- [ ] Implement `NoticeComposerBar.kt`: expandable multiline input canvas with docked action icons (📎 Attach PDF, 📷 Add Photos, 🎙️ Voice Record, 🚀 Send Button).
- [ ] Implement `NoticeAttachmentPreviews.kt`: clean PDF preview card and horizontal multi-photo carousel with delete badges.
- [ ] Implement live Gemini voice recording UI (pulsing dot, elapsed timer, audio amplitude EQ waveform bars, and transcribing spinner).

### Task 3: Build Notice Type Switcher & Specialized Screens (`PostNoticeScreen.kt`)
- [ ] Build top radio button group: `(•) General notice`, `( ) Class cancellation`, `( ) Interactive poll`.
- [ ] Build General Notice mode: AI title auto-detection banner and fallback manual title box (hidden by default, appears only when AI detection fails).
- [ ] Build Class Cancellation mode: assigned course selector, Today/Tomorrow/Custom date dropdown.
- [ ] Build Interactive Poll mode: question field, dynamic options list (add/remove options), anonymous/multiple-choice checkboxes, and duration dropdown.

### Task 4: Integrate with `ClassMatePublishActivity` Backend Pipeline
- [ ] Connect `ClassMatePublishActivity` to host `PostNoticeScreen` via `ComposeView` when `!upload`.
- [ ] Wire `ClassMateAuthApi.ai("auto_notice")` for automated title extraction from body.
- [ ] Wire `ClassMateAuthApi.rpc` (`post_notice`, `post_cancellation_notice`, `publish_batch_poll`).
- [ ] Wire attachment upload progress via `NoticeRepository.uploadSupabaseNoticeAttachment`.
- [ ] Wire Gemini voice transcription via `AIService.transcribeAudio`.
- [ ] Wire `OfflineNoticeQueue.enqueue` for zero-data-loss offline submission.

### Task 5: Verification & Testing
- [ ] Run `./gradlew assembleDebug` to verify compilation.
- [ ] Verify light and dark mode parity.
- [ ] Verify keyboard insets (`imePadding`) so input and attachments fluidly sit above the soft keyboard.
