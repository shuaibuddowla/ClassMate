# ClassMate

ClassMate is a Kotlin Android app for the MBSTU CSE-22 class community. It brings notices, AI summaries, timetable updates, library resources, chat, polls, attendance, widgets, and admin publishing tools into one Firebase-backed student workflow.

## Highlights

- Clean Facebook-style login and step-by-step account creation.
- Modern notice feed with pins, reactions, comments, reminders, sharing, and offline-first caching.
- AI notice summaries for students and AI-assisted notice drafting for admins.
- Admin notice composer with markdown-style formatting, write/preview pills, attachment support, and deadline/cancellation workflows.
- Timetable, class cancellation, substitute class, assignment deadline, and class-test deadline support.
- PDF Library with subject organization, search, recent uploads, visible downloaded/offline files, and open/delete cache actions.
- Real-time class chat and direct messages with smoother bottom-of-chat behavior.
- Home/widget timetable surfaces for quick schedule checks.
- Polls, attendance tools, seat plans, results, profile management, and admin utilities.
- Firebase Cloud Functions and Firestore rules included.

## Tech Stack

- Kotlin, Android Views, Material Components
- Gradle Kotlin DSL
- Firebase Auth, Firestore, Messaging, Cloud Functions
- Room for local/offline cache
- WorkManager for reminders and background sync
- Groq for primary AI generation with Gemini fallback
- OneSignal and Telegram integrations for notification workflows
- Glide, Lottie, Shimmer, AndroidX Navigation

## Project Structure

```text
app/                    Android app source
functions/              Firebase Cloud Functions source
firestore.rules         Firestore security rules
firestore.indexes.json  Firestore index definitions
firebase.json           Firebase project configuration
gradle/                 Gradle wrapper and version catalog
```

## Local Setup

1. Install Android Studio and open this repository.
2. Let Android Studio create `local.properties` with `sdk.dir`.
3. Add required local build values to `local.properties`:

```properties
GITHUB_LIBRARY_TOKEN=your_token
GITHUB_OWNER=your_github_owner
GITHUB_REPO=your_library_repo
GITHUB_RELEASE_TAG=your_release_tag
ONESIGNAL_APP_ID=your_onesignal_app_id
ONESIGNAL_REST_API_KEY=your_onesignal_rest_api_key
TELEGRAM_BOT_TOKEN=your_telegram_bot_token
TELEGRAM_CHANNEL_ID=your_telegram_channel_id
GROQ_API_KEY=your_groq_api_key
GEMINI_API_KEY=your_gemini_api_key
```

4. Confirm `app/google-services.json` belongs to your Firebase project.
5. Build:

```powershell
.\gradlew.bat --no-daemon --console=plain :app:assembleDebug
```

## Firebase Functions

```powershell
cd functions
npm install
npm run build
```

Deploy from the project root:

```powershell
firebase deploy
```

## Security Notes

Do not commit `local.properties`, APK files, keystores, API secrets, or release artifacts. Firebase Android API keys in `google-services.json` should be restricted in Google Cloud/Firebase.
