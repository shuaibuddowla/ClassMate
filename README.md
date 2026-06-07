# ClassMate

ClassMate is an Android app for class communities. It brings notices, timetable reminders, PDF library management, polls, attendance tools, profile management, and real-time chat into one Firebase-backed student workflow.

## Features

- Firebase Authentication based sign in and registration.
- Notice feed with comments, reactions, reminders, pins, sharing, and offline-first caching.
- Timetable and academic calendar support with local persistence and scheduled reminders.
- PDF library with upload, subject organization, search, recent files, and notification handling.
- Real-time class chat and direct messaging flows.
- AI-assisted notice summaries and drafts powered by Groq, with Gemini fallback support.
- Polls, attendance, result uploads, seat plans, onboarding, profile editing, and admin tools.
- Firebase Cloud Functions and Firestore rules included with the project.

## Tech Stack

- Kotlin and Android Views
- Gradle Kotlin DSL
- Firebase Auth, Firestore, Storage, Messaging, and Cloud Functions
- Room for local cache
- WorkManager for background sync and reminders
- Groq API for primary AI notice generation, with Gemini API fallback
- Material Components, Navigation, Glide, Lottie, and Shimmer

## Project Structure

```text
app/                 Android application source
functions/           Firebase Cloud Functions source
firestore.rules      Firestore security rules
firestore.indexes.json
firebase.json        Firebase project configuration
gradle/              Gradle wrapper and version catalog
```

## Local Setup

1. Install Android Studio with JDK 17.
2. Clone the repository and open the root folder in Android Studio.
3. Create `local.properties` in the project root. Android Studio usually adds `sdk.dir` automatically.
4. Add the app library release settings used by `app/build.gradle.kts`:

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

5. Confirm `app/google-services.json` matches the Firebase project used for the app.
6. Keep Groq and Gemini keys in `local.properties`; they are injected through `BuildConfig` and should not be committed.
7. Build the debug APK:

```powershell
.\gradlew.bat --no-daemon --console=plain :app:assembleDebug
```

## Firebase Functions

Install dependencies and build the functions package:

```powershell
cd functions
npm install
npm run build
```

Deploy Firebase resources from the project root when ready:

```powershell
firebase deploy
```

## Repository Notes

Generated APKs, IDE state, crash logs, UI inspection dumps, and local staging folders are intentionally ignored. The repository is intended to contain the source code and configuration needed to build and maintain ClassMate, not local machine artifacts.
