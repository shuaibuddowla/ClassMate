# ClassMate

**ClassMate** is a comprehensive academic companion app built for the MBSTU CSE-22 batch. It unifies notices, timetable management, library resources, polls, and administrative workflows into a single Firebase-backed platform — designed with a mobile-first experience for both students and administrators.

---

## Table of Contents

- [Features](#features)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Local Setup](#local-setup)
- [Firebase Functions](#firebase-functions)
- [Deploying Your Own Instance](#deploying-your-own-instance)
- [Security Notes](#security-notes)
- [Disclaimer](#disclaimer)

---

## Features

### Notice Management
- Modern notice feed with pinning, reactions, comments, reminders, sharing, and offline-first caching
- AI-generated notice summaries for students
- AI-assisted notice drafting for administrators
- Rich notice composer with markdown-style formatting, write/preview modes, and attachment support
- Deadline management workflows
- Class cancellation and substitute-class notices
- Assignment and class-test deadline notices

### Academic Management
- Complete timetable and schedule management
- Home screen timetable widgets
- Quick access to ongoing and upcoming classes
- Attendance management tools
- Seat plan and result management

### Library System
- Subject-wise PDF organization with search
- Recent uploads section
- Downloaded/offline file visibility
- Open, manage, and delete cached resources
- GitHub-powered resource distribution workflow

### Communication
- Polls and voting system
- Profile management
- Administrative moderation tools

### Platform
- Offline-first architecture
- Background reminders and synchronization
- Firebase Cloud Functions support
- Firestore security rules
- Notifications via OneSignal and Telegram integrations

---

## Tech Stack

| Layer | Technologies |
|---|---|
| **Android** | Kotlin, Android Views, Material Components, AndroidX Navigation |
| **Backend** | Firebase Auth, Cloud Firestore, Cloud Functions, FCM, Firebase Storage |
| **Local Storage** | Room Database, offline-first caching |
| **Background** | WorkManager |
| **AI** | Groq (primary), Gemini (fallback) |
| **Notifications** | OneSignal, Telegram Bot |
| **UI & Media** | Glide, Lottie, Shimmer |
| **Build** | Gradle Kotlin DSL |

---

## Project Structure

```
app/                    Android application source
functions/              Firebase Cloud Functions source
firestore.rules         Firestore security rules
firestore.indexes.json  Firestore index definitions
firebase.json           Firebase project configuration
gradle/                 Gradle wrapper and version catalog
```

---

## Local Setup

### 1. Install Android Studio

Install Android Studio and open this repository. Android Studio will generate `local.properties` with your SDK path automatically.

### 2. Configure Build Credentials

Add the following to `local.properties`:

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

### 3. Add Firebase Configuration

Download `google-services.json` from your Firebase project and place it at:

```
app/google-services.json
```

### 4. Build

```powershell
.\gradlew.bat --no-daemon --console=plain :app:assembleDebug
```

---

## Firebase Functions

Install dependencies and build:

```powershell
cd functions
npm install
npm run build
```

Deploy functions only:

```powershell
firebase deploy --only functions
```

Deploy the full Firebase backend:

```powershell
firebase deploy
```

---

## Deploying Your Own Instance

> ClassMate is **not** a multi-tenant platform. There is no classroom-code system and no teacher-student invite flow. It was built for a single academic community where all users share one backend managed by administrators.
>
> To use ClassMate for your own batch, department, or student community, you must deploy your own instance.

### How It Works

1. Fork or download this repository
2. Create your own Firebase project and configure all required services
3. Add your API credentials to `local.properties`
4. Generate a signed release APK
5. Distribute the APK to your students

Once installed, every student automatically connects to your backend. Your Firebase project becomes your own independent ClassMate ecosystem, completely isolated from other deployments.

---

### Step-by-Step Firebase Setup

#### Step 1 — Create a Firebase Project

1. Visit [Firebase Console](https://console.firebase.google.com)
2. Click **Create Project** and follow the prompts

#### Step 2 — Register Your Android App

1. Go to **Project Settings → Add App → Android**
2. Enter your package name and register
3. Download `google-services.json` and place it at `app/google-services.json`

#### Step 3 — Enable Firebase Services

Enable the following in your Firebase project:

- **Authentication** — Google Sign-In and Email/Password providers
- **Cloud Firestore** — create a Firestore database
- **Firebase Storage** — create a storage bucket
- **Firebase Cloud Messaging** — for push notifications
- **Cloud Functions** — for backend operations

#### Step 4 — Configure SHA Certificates

Required for Google Authentication:

```bash
# Debug SHA
keytool -list -v -alias androiddebugkey -keystore %USERPROFILE%\.android\debug.keystore

# Release SHA — generate your release keystore and register both SHA-1 and SHA-256 in Firebase Project Settings
```

#### Step 5 — Deploy Firestore Rules

```powershell
firebase deploy --only firestore:rules
```

#### Step 6 — Deploy Cloud Functions

```powershell
cd functions
npm install
npm run build
firebase deploy --only functions
```

#### Step 7 — Configure Third-Party Services

Set up and add credentials for the following to `local.properties`:

- Groq API
- Gemini API
- OneSignal
- Telegram Bot
- GitHub Release repository (for library file workflows)

#### Step 8 — Generate a Signed APK

In Android Studio: **Build → Generate Signed Bundle / APK → APK**

Create or select a keystore and build the release APK.

#### Step 9 — Distribute

Share the APK via GitHub Releases, Google Drive, Telegram, WhatsApp, or any channel that works for your community. Students install it and connect to your backend automatically.

---

### Administrator Model

ClassMate uses a centralized administration model. The deployer becomes the initial **Super Admin**.

**Super Admin capabilities:**
- Publish notices and manage timetable data
- Upload library resources and manage polls
- Control attendance systems
- Manage user permissions and roles
- Promote or remove administrators
- Moderate content and manage student accounts

---

### Intended Use Cases

ClassMate is best suited for:

- University batches and academic departments
- Student clubs and research groups
- Campus communities and small educational organizations

It is a self-hosted academic communication platform, not a global classroom service.

---

## Security Notes

Never commit the following to version control:

- `local.properties`
- APK or AAB files
- Keystores
- API secrets or service account keys
- Release artifacts

Always configure Firebase Security Rules properly and restrict your Firebase API keys before going to production.

---

## Disclaimer

ClassMate is not a SaaS platform. Each institution, department, batch, or community is expected to deploy and maintain its own Firebase project, credentials, backend configuration, and application build.

By deploying your own instance, you are responsible for security, user management, infrastructure costs, and operational reliability.
