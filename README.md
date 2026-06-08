# ClassMate

ClassMate is a comprehensive academic companion app built for the MBSTU CSE-22 class community. It brings notices, AI-powered summaries, timetable management, library resources, chat, polls, attendance tools, widgets, and administrative publishing workflows into a unified Firebase-backed platform.

The project was originally designed to simplify academic communication and resource sharing within a university batch while providing a modern, mobile-first experience for students and administrators.

---

## Highlights

### Notice Management

* Modern notice feed with pinning, reactions, comments, reminders, sharing, and offline-first caching.
* AI-generated notice summaries for students.
* AI-assisted notice drafting for administrators.
* Rich notice composer with markdown-style formatting.
* Write and preview modes.
* Attachment support.
* Deadline management workflows.
* Class cancellation and substitute-class notices.
* Assignment and class-test deadline notices.

### Academic Management

* Complete timetable and schedule management.
* Home screen timetable widgets.
* Quick access to ongoing and upcoming classes.
* Attendance management tools.
* Seat plan management.
* Result management and academic utilities.

### Library System

* Subject-wise PDF organization.
* Searchable library.
* Recent uploads section.
* Downloaded/offline file visibility.
* Open, manage, and delete cached resources.
* GitHub-powered resource distribution workflow.

### Communication

* Real-time batch chat.
* Direct messaging support.
* Polls and voting system.
* Profile management.
* Administrative moderation tools.

### Platform Features

* Offline-first architecture.
* Background reminders and synchronization.
* Firebase Cloud Functions support.
* Firestore security rules included.
* Notification workflows through OneSignal and Telegram integrations.

---

## Tech Stack

### Android

* Kotlin
* Android Views
* Material Components
* AndroidX Navigation

### Backend

* Firebase Authentication
* Cloud Firestore
* Firebase Cloud Functions
* Firebase Cloud Messaging (FCM)
* Firebase Storage

### Local Storage

* Room Database
* Offline-first caching architecture

### Background Processing

* WorkManager

### AI Integrations

* Groq (Primary)
* Gemini (Fallback)

### Additional Services

* OneSignal
* Telegram Bot Integration

### UI & Utilities

* Glide
* Lottie
* Shimmer

### Build System

* Gradle Kotlin DSL

---

## Project Structure

```text
app/                    Android application source
functions/              Firebase Cloud Functions source
firestore.rules         Firestore security rules
firestore.indexes.json  Firestore index definitions
firebase.json           Firebase project configuration
gradle/                 Gradle wrapper and version catalog
```

---

# Local Setup

## 1. Install Android Studio

Install Android Studio and open this repository.

---

## 2. Create Local Properties

Allow Android Studio to generate the initial `local.properties` file containing:

```properties
sdk.dir=YOUR_ANDROID_SDK_PATH
```

---

## 3. Configure Build Credentials

Add the required values to `local.properties`.

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

---

## 4. Configure Firebase

Download your Firebase configuration file and place it inside:

```text
app/google-services.json
```

---

## 5. Build the Application

```powershell
.\gradlew.bat --no-daemon --console=plain :app:assembleDebug
```

---

# Firebase Functions

Install dependencies:

```powershell
cd functions
npm install
npm run build
```

Deploy functions:

```powershell
firebase deploy --only functions
```

Deploy the complete Firebase backend:

```powershell
firebase deploy
```

---

# Using ClassMate For Your Own Batch

## Important

ClassMate is **not** a multi-tenant platform like Google Classroom, Microsoft Teams, Moodle, or Canvas.

There is no classroom-code system.

Teachers cannot create classrooms and invite students using join codes.

The application was originally designed for a single academic community (MBSTU CSE-22), where every user connects to the same backend managed by administrators.

If you want to use ClassMate for your own university batch, department, student club, research group, or academic community, you must deploy your own instance of the application.

---

## How Deployment Works

1. Download or fork this repository.
2. Create your own Firebase project.
3. Configure all required Firebase services.
4. Configure API credentials.
5. Generate a signed APK.
6. Distribute the APK to your students.
7. Students install the APK and automatically connect to your backend.

After deployment, your users will be completely isolated from other ClassMate deployments.

Your Firebase project becomes your own independent ClassMate ecosystem.

---

## Administrator Model

Unlike Google Classroom, ClassMate follows a centralized administration model.

The owner of the deployment becomes the initial Super Admin and controls the platform.

The Super Admin can:

* Publish notices
* Manage timetable data
* Upload library resources
* Manage polls
* Control attendance systems
* Manage user permissions
* Promote or remove administrators
* Moderate content
* Manage student accounts

Every user of your APK connects only to your configured Firebase backend.

---

# Creating Your Own Firebase Backend

## Step 1: Create a Firebase Project

1. Visit Firebase Console.
2. Click **Create Project**.
3. Enter a project name.
4. Complete project creation.

---

## Step 2: Register Android Application

1. Open Project Settings.
2. Click **Add App → Android**.
3. Enter your package name.
4. Register the application.
5. Download `google-services.json`.

Place it inside:

```text
app/google-services.json
```

---

## Step 3: Enable Firebase Services

Enable the following services:

### Authentication

Recommended providers:

* Google Sign-In
* Email/Password

### Cloud Firestore

Create a Firestore database.

### Firebase Storage

Create a Storage bucket.

### Firebase Cloud Messaging

Enable push notifications.

### Cloud Functions

Enable Cloud Functions for backend operations.

---

## Step 4: Configure SHA Certificates

For Google Authentication to work correctly:

### Debug SHA

```bash
keytool -list -v -alias androiddebugkey -keystore %USERPROFILE%\.android\debug.keystore
```

### Release SHA

Generate your release keystore and register its SHA-1 and SHA-256 values inside Firebase Project Settings.

---

## Step 5: Deploy Firestore Rules

```powershell
firebase deploy --only firestore:rules
```

---

## Step 6: Deploy Cloud Functions

```powershell
cd functions
npm install
npm run build

firebase deploy --only functions
```

---

## Step 7: Configure Third-Party Services

Configure:

* Groq API
* Gemini API
* OneSignal
* Telegram Bot
* GitHub Release repository (for library workflows if applicable)

Add all credentials to `local.properties`.

---

## Step 8: Generate a Signed Release APK

Inside Android Studio:

```text
Build
└── Generate Signed Bundle / APK
    └── APK
```

Create a new keystore or select an existing one.

Build the release APK.

---

## Step 9: Distribute The APK

You may distribute the generated APK using:

* GitHub Releases
* Google Drive
* Telegram
* WhatsApp
* University Groups
* Institution Websites

Once installed, students automatically connect to your backend and become part of your ClassMate deployment.

---

# Intended Usage

ClassMate is best suited for:

* University batches
* Academic departments
* Student clubs
* Research groups
* Campus communities
* Small educational organizations

It is designed as a self-hosted academic communication platform rather than a global classroom service.

---

# Security Notes

Never commit:

* local.properties
* APK files
* AAB files
* Keystores
* API secrets
* Service account keys
* Release artifacts

Restrict Firebase API keys and configure Firebase Security Rules properly before production deployment.

---

# Disclaimer

ClassMate is not a Software-as-a-Service (SaaS) platform.

Each institution, department, batch, or community is expected to deploy and maintain its own Firebase project, credentials, backend configuration, and application build.

By deploying your own instance, you are responsible for maintaining security, user management, infrastructure costs, and operational reliability.
