#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <SPI.h>
#include <MFRC522.h>
#include <ArduinoJson.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <Preferences.h>

// ==========================================
// 1. NETWORK & MULTI-WIFI FALLBACK CONFIG
// ==========================================
struct WiFiNetwork {
  const char* ssid;
  const char* password;
  const char* label;
};

// ⚠️ CONFIGURED WI-FI NETWORKS (Automatically loops if signal lost):
const WiFiNetwork CONFIGURED_WIFIS[] = {
  { "Aaa",            "12345678", "Mobile hotspot"    },
  { "CSE Class Zone", "csembstu", "CSE Class Zone"    },
  { "CSE Lab",        "csembstu", "Dept Campus Wi-Fi" }
};
const size_t WIFI_NETWORK_COUNT = sizeof(CONFIGURED_WIFIS) / sizeof(CONFIGURED_WIFIS[0]);

// ClassMate Attendance API Endpoint
const char* SERVER_URL    = "https://classmatebd.vercel.app/api/scan";
const char* DEVICE_ID     = "ESP32-READER-01";

// ==========================================
// 2. PIN DEFINITIONS (ESP32 38-Pin Layout)
// ==========================================
// RC522 SPI Pins:
// SDA (SS) -> GPIO 5
// SCK      -> GPIO 18
// MOSI     -> GPIO 23
// MISO     -> GPIO 19
// RST      -> GPIO 4   (GPIO 22 is used for OLED I2C SCL)
// 3.3V     -> 3V3      (⚠️ NEVER connect RC522 to 5V!)
// GND      -> GND
#define PIN_SPI_SS    5
#define PIN_SPI_SCK   18
#define PIN_SPI_MOSI  23
#define PIN_SPI_MISO  19
#define PIN_RC522_RST 4

// I2C OLED Display (0.96 inch SSD1306 128x64):
// SDA      -> GPIO 21
// SCL      -> GPIO 22
#define PIN_OLED_SDA  21
#define PIN_OLED_SCL  22
#define SCREEN_WIDTH  128
#define SCREEN_HEIGHT 64
#define OLED_RESET    -1

Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);
bool oledAvailable = false;
byte oledAddress   = 0x3C;
SemaphoreHandle_t oledMutex = NULL;

// Feedback Peripherals:
// Buzzer Signal -> GPIO 27 (Connect Buzzer VCC to VIN 5V for maximum loudness!)
// Green LED     -> GPIO 25 (via 220-330 ohm resistor)
// Red LED       -> GPIO 26 (via 220-330 ohm resistor)
#define PIN_BUZZER    27
#define PIN_LED_GREEN 25
#define PIN_LED_RED   26

// Audio Tone Mode (Set true for tone generation, false for active buzzer pulsed digital writes)
#define BUZZER_USE_TONE true

// ==========================================
// 3. EDGE CACHE & ASYNC QUEUE STRUCTURES
// ==========================================
MFRC522 rfid(PIN_SPI_SS, PIN_RC522_RST);

String lastUid = "";
unsigned long lastScanTime = 0;
const unsigned long COOLDOWN_MS = 1200;

// Screen Modes
enum ScreenMode {
  SCREEN_SLEEP,
  SCREEN_IDLE,
  SCREEN_COURSE_ACTIVE,
  SCREEN_CARD_READ,
  SCREEN_RESULT
};

volatile ScreenMode currentScreenMode = SCREEN_IDLE;
volatile bool isDeviceSleeping = false;
volatile bool screenNeedsRedraw = true;
unsigned long lastMarqueeTime = 0;
unsigned long lastRfidHealthCheck = 0;
unsigned long lastIdleRedraw = 0;
int marqueeX = 128;

String activeCourseCode  = "CSE 2105";
String activeCourseTitle = "ClassMate Attendance Station";
String connectedWifiSsid = "Connecting...";

// ------------------------------------------
// LOCAL IN-MEMORY ROSTER CACHE
// ------------------------------------------
struct StudentCacheItem {
  char uid[12];        // e.g. "F9831C02"
  char studentId[16];   // e.g. "CE25001" or "081"
  bool present;        // Marked present in current session
};

#define MAX_CACHED_STUDENTS 160
StudentCacheItem localRoster[MAX_CACHED_STUDENTS];
size_t localRosterCount = 0;
String cachedSessionId = "";
portMUX_TYPE rosterMux = portMUX_INITIALIZER_UNLOCKED;

// ------------------------------------------
// PERSISTENT FAIL-SAFE SCAN BUFFER & NVS JOURNAL
// ------------------------------------------
#define MAX_PENDING_SCANS 120
#define BATCH_UPLOAD_SIZE 20

struct OfflineScan {
  char cardUid[12];
  uint32_t scanMillis;
};

OfflineScan pendingScans[MAX_PENDING_SCANS];
volatile int pendingScanCount = 0;
SemaphoreHandle_t syncMutex = NULL;
Preferences scanPrefs;
TaskHandle_t syncTaskHandle = NULL;

// Helper: Thread-safe OLED mutex access
bool takeOledLock(TickType_t waitTicks = pdMS_TO_TICKS(100)) {
  if (oledMutex == NULL) return true;
  return (xSemaphoreTake(oledMutex, waitTicks) == pdTRUE);
}

void releaseOledLock() {
  if (oledMutex != NULL) {
    xSemaphoreGive(oledMutex);
  }
}

// Save pending scans to Non-Volatile Flash (NVS)
void savePendingScansToFlash() {
  OfflineScan localCopy[MAX_PENDING_SCANS];
  int countCopy = 0;

  if (syncMutex != NULL && xSemaphoreTake(syncMutex, pdMS_TO_TICKS(100)) == pdTRUE) {
    countCopy = pendingScanCount;
    if (countCopy > 0) {
      memcpy(localCopy, (const void*)pendingScans, countCopy * sizeof(OfflineScan));
    }
    xSemaphoreGive(syncMutex);
  } else {
    return;
  }

  scanPrefs.begin("cm_scans", false);
  scanPrefs.putInt("count", countCopy);
  if (countCopy > 0) {
    scanPrefs.putBytes("list", (const void*)localCopy, countCopy * sizeof(OfflineScan));
  } else {
    scanPrefs.remove("list");
  }
  scanPrefs.end();
}

// Load pending scans from Non-Volatile Flash (NVS) on startup
void loadPendingScansFromFlash() {
  if (syncMutex != NULL && xSemaphoreTake(syncMutex, pdMS_TO_TICKS(200)) == pdTRUE) {
    scanPrefs.begin("cm_scans", false);
    int count = scanPrefs.getInt("count", 0);
    if (count > MAX_PENDING_SCANS) count = MAX_PENDING_SCANS;
    if (count < 0) count = 0;
    pendingScanCount = 0;

    if (count > 0) {
      size_t expectedSize = count * sizeof(OfflineScan);
      size_t readSize = scanPrefs.getBytes("list", (void*)pendingScans, expectedSize);
      if (readSize == expectedSize) {
        pendingScanCount = count;
        Serial.printf("[FLASH-RECOVERY] Restored %d offline scans from NVS Flash!\n", pendingScanCount);
      } else {
        Serial.printf("[FLASH-RECOVERY] Size mismatch in NVS (expected %d, got %d). Resetting.\n", expectedSize, readSize);
        scanPrefs.remove("list");
        scanPrefs.putInt("count", 0);
      }
    }
    scanPrefs.end();
    xSemaphoreGive(syncMutex);
  }
}

// Thread-safe enqueue: adds scan to memory and persists to Flash immediately
bool enqueuePendingScan(const char* uid, uint32_t timestamp) {
  bool added = false;
  if (syncMutex != NULL && xSemaphoreTake(syncMutex, pdMS_TO_TICKS(100)) == pdTRUE) {
    // Check if card is already in the pending queue to avoid duplicate batch submissions
    bool alreadyQueued = false;
    for (int i = 0; i < pendingScanCount; i++) {
      if (strcmp(pendingScans[i].cardUid, uid) == 0) {
        alreadyQueued = true;
        break;
      }
    }

    if (!alreadyQueued && pendingScanCount < MAX_PENDING_SCANS) {
      strncpy(pendingScans[pendingScanCount].cardUid, uid, sizeof(pendingScans[0].cardUid) - 1);
      pendingScans[pendingScanCount].cardUid[sizeof(pendingScans[0].cardUid) - 1] = '\0';
      pendingScans[pendingScanCount].scanMillis = timestamp;
      pendingScanCount++;
      added = true;
      Serial.printf("[QUEUE] Enqueued UID %s (Total pending: %d)\n", uid, pendingScanCount);
    }
    xSemaphoreGive(syncMutex);
  }

  if (added) {
    savePendingScansToFlash();
  }
  return added;
}

// ==========================================
// 4. AUDIO HELPERS
// ==========================================
void soundBeep(int freq, int durationMs) {
#if BUZZER_USE_TONE
  tone(PIN_BUZZER, freq, durationMs);
  delay(durationMs);
  noTone(PIN_BUZZER);
#else
  digitalWrite(PIN_BUZZER, HIGH);
  delay(durationMs);
  digitalWrite(PIN_BUZZER, LOW);
#endif
}

// Instant tap chime upon card touch
void soundInstantTap() {
  soundBeep(2700, 70);
}

// Crisp 2-tone chime when validated as PRESENT
void soundPresentChime() {
  soundBeep(2600, 60);
  delay(25);
  soundBeep(3300, 90);
}

// Warning beeps for unregistered or wrong-batch cards
void soundWarningBeeps() {
  for (int i = 0; i < 3; i++) {
    digitalWrite(PIN_LED_RED, HIGH);
    soundBeep(850, 90);
    digitalWrite(PIN_LED_RED, LOW);
    delay(50);
  }
}

// ==========================================
// 5. DISPLAY ENGINE & ANIMATIONS
// ==========================================

// Base Idle Screen (When no active course session is open)
void drawBaseIdleScreen() {
  if (!oledAvailable) return;
  if (!takeOledLock()) return;

  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE, SSD1306_BLACK);

  // Top header status bar
  display.setTextSize(1);
  display.setCursor(8, 2);
  display.print("CLASSMATE STATION");
  display.drawLine(0, 11, 127, 11, SSD1306_WHITE);

  // Center RFID Card Graphic with chip
  display.drawRoundRect(36, 16, 56, 26, 4, SSD1306_WHITE);
  display.drawCircle(46, 29, 3, SSD1306_WHITE); // Chip circle
  display.drawLine(54, 24, 82, 24, SSD1306_WHITE);
  display.drawLine(54, 29, 82, 29, SSD1306_WHITE);
  display.drawLine(54, 34, 76, 34, SSD1306_WHITE);

  // Bottom prompt bar
  display.drawLine(0, 46, 127, 46, SSD1306_WHITE);
  display.setTextSize(1);
  display.setCursor(4, 52);
  if (pendingScanCount > 0) {
    display.printf("STANDBY [SYNC: %d]", pendingScanCount);
  } else if (WiFi.status() == WL_CONNECTED) {
    display.print("STANDBY: TAP CARD");
  } else {
    display.print("WIFI: RECONNECTING");
  }

  display.display();
  releaseOledLock();
}

// Active Course Screen: Big Course Code + Smooth Marquee Ticker for Subject Name
void drawActiveCourseScreen() {
  if (!oledAvailable) return;
  if (!takeOledLock()) return;

  display.clearDisplay();
  display.setTextWrap(false);
  display.setTextColor(SSD1306_WHITE, SSD1306_BLACK);

  // Top Line: Course Code in large bold font (TextSize 2)
  display.setTextSize(2);
  int codeX = (128 - (activeCourseCode.length() * 12)) / 2;
  if (codeX < 0) codeX = 0;
  display.setCursor(codeX, 4);
  display.print(activeCourseCode);

  display.drawLine(0, 23, 127, 23, SSD1306_WHITE);

  // Center Line: Smooth Scrolling Marquee for Course Title
  display.setTextSize(1);
  display.setCursor(marqueeX, 31);
  display.print(activeCourseTitle);

  display.drawLine(0, 47, 127, 47, SSD1306_WHITE);

  // Bottom Line: Instruction prompt with sync badge if pending
  display.setTextSize(1);
  if (pendingScanCount > 0) {
    display.setCursor(4, 52);
    display.printf("TAP CARD | SYNC: %d", pendingScanCount);
  } else {
    display.setCursor(16, 52);
    display.print(">> TAP ID CARD <<");
  }

  display.display();
  releaseOledLock();

  // Advance marquee ticker smoothly
  int titleWidth = activeCourseTitle.length() * 6;
  marqueeX -= 3;
  if (marqueeX < -(titleWidth + 12)) {
    marqueeX = 128;
  }
}

// Step 1: Instantly render ONLY the Student ID on OLED in large bold font
void showOnlyStudentId(const char* studentId) {
  if (!oledAvailable) return;
  if (!takeOledLock()) return;

  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE, SSD1306_BLACK);

  display.setTextSize(1);
  display.setCursor(32, 6);
  display.print("STUDENT ID");
  display.drawLine(14, 18, 114, 18, SSD1306_WHITE);

  display.setTextSize(2);
  String idStr = String(studentId);
  int xPos = (128 - (idStr.length() * 12)) / 2;
  if (xPos < 0) xPos = 0;
  display.setCursor(xPos, 32);
  display.print(idStr);

  display.display();
  releaseOledLock();
}

// Step 2 (Success): Show Checkmark (Tick) Sign with OK! PRESENT
void showCheckmarkTickAnimation() {
  if (!oledAvailable) return;
  if (!takeOledLock()) return;

  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);

  // Outer container box with rounded corners
  display.drawRoundRect(10, 6, 108, 52, 6, SSD1306_WHITE);

  // White filled circle for the badge
  display.fillCircle(36, 32, 16, SSD1306_WHITE);

  // Bold black Checkmark (Tick) inside the white circle
  display.drawLine(27, 32, 33, 38, SSD1306_BLACK);
  display.drawLine(27, 33, 33, 39, SSD1306_BLACK);
  display.drawLine(28, 33, 34, 39, SSD1306_BLACK);

  display.drawLine(33, 38, 45, 25, SSD1306_BLACK);
  display.drawLine(33, 39, 45, 26, SSD1306_BLACK);
  display.drawLine(34, 39, 46, 26, SSD1306_BLACK);

  display.setTextSize(2);
  display.setCursor(58, 20);
  display.print("OK!");

  display.setTextSize(1);
  display.setCursor(58, 40);
  display.print("PRESENT");

  display.display();
  releaseOledLock();
}

// Step 2 (Duplicate Scan): Show Cross (X) Sign with ALREADY PRESENTED
void showCrossAlreadyAnimation() {
  if (!oledAvailable) return;
  if (!takeOledLock()) return;

  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);

  // Outer container box with rounded corners
  display.drawRoundRect(10, 6, 108, 52, 6, SSD1306_WHITE);

  // White filled circle for the badge
  display.fillCircle(36, 32, 16, SSD1306_WHITE);

  // Bold black Cross (X) inside the white circle
  display.drawLine(27, 23, 45, 41, SSD1306_BLACK);
  display.drawLine(28, 23, 44, 39, SSD1306_BLACK);
  display.drawLine(28, 24, 44, 40, SSD1306_BLACK);
  display.drawLine(29, 24, 45, 40, SSD1306_BLACK);
  display.drawLine(28, 25, 44, 41, SSD1306_BLACK);

  display.drawLine(45, 23, 27, 41, SSD1306_BLACK);
  display.drawLine(44, 23, 28, 39, SSD1306_BLACK);
  display.drawLine(44, 24, 28, 40, SSD1306_BLACK);
  display.drawLine(45, 24, 29, 40, SSD1306_BLACK);
  display.drawLine(44, 25, 28, 41, SSD1306_BLACK);

  // Right-side Text Information
  display.setTextSize(1);
  display.setCursor(58, 22);
  display.print("ALREADY");
  display.setCursor(58, 36);
  display.print("PRESENTED");

  display.display();
  releaseOledLock();
}

// Out-of-Session Warning Screen: Displays Student ID and "Attendance Not Started"
void showAttendanceNotStartedScreen(const char* studentId, bool isRegistered) {
  if (!oledAvailable) return;
  if (!takeOledLock()) return;

  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE, SSD1306_BLACK);

  // Outer warning box
  display.drawRoundRect(4, 2, 120, 60, 4, SSD1306_WHITE);

  // Title: ATTENDANCE NOT STARTED
  display.setTextSize(1);
  display.setCursor(22, 7);
  display.print("ATTENDANCE NOT");
  display.setCursor(43, 19);
  display.print("STARTED");

  display.drawLine(10, 31, 117, 31, SSD1306_WHITE);

  // Student identification or Unknown notice
  if (isRegistered && studentId != NULL && strlen(studentId) > 0) {
    display.setTextSize(1);
    String idStr = "ID: " + String(studentId);
    int xPos = (128 - (idStr.length() * 6)) / 2;
    if (xPos < 8) xPos = 8;
    display.setCursor(xPos, 36);
    display.print(idStr);
  } else {
    display.setTextSize(1);
    display.setCursor(8, 36);
    display.print("CARD NOT REGISTERED");
  }

  // Teacher instruction footer
  display.setTextSize(1);
  display.setCursor(7, 48);
  display.print("Wait for Teacher/CR");

  display.display();
  releaseOledLock();
}

// General Warning Screen for unregistered cards during live session
void showWarningScreen(const char* title, const char* subtitle, String uid) {
  if (!oledAvailable) return;
  if (!takeOledLock()) return;

  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE, SSD1306_BLACK);

  display.setTextSize(1);
  display.setCursor(18, 2);
  display.println(title);
  display.drawLine(0, 12, 127, 12, SSD1306_WHITE);

  display.setCursor(2, 18);
  display.print("UID: ");
  display.println(uid);

  display.setCursor(2, 30);
  display.println(subtitle);

  display.drawLine(0, 44, 127, 44, SSD1306_WHITE);
  display.setCursor(2, 50);
  display.println("Check Admin Portal");

  display.display();
  releaseOledLock();
}

// ==========================================
// 6. HARDWARE INITIALIZATION (I2C BUS RECOVERY)
// ==========================================
bool initOLEDDisplay() {
  Serial.println("\n[OLED] Performing I2C Bus Clean Reset & Probe...");

  // 1. Force I2C bus recovery (clock SCL 9 times to free any stuck slave)
  pinMode(PIN_OLED_SDA, INPUT_PULLUP);
  pinMode(PIN_OLED_SCL, OUTPUT);
  for (int i = 0; i < 9; i++) {
    digitalWrite(PIN_OLED_SCL, HIGH);
    delayMicroseconds(5);
    digitalWrite(PIN_OLED_SCL, LOW);
    delayMicroseconds(5);
  }
  // Send I2C Stop condition
  pinMode(PIN_OLED_SDA, OUTPUT);
  digitalWrite(PIN_OLED_SDA, LOW);
  delayMicroseconds(5);
  digitalWrite(PIN_OLED_SCL, HIGH);
  delayMicroseconds(5);
  digitalWrite(PIN_OLED_SDA, HIGH);
  delayMicroseconds(5);

  // Return to open-drain input pullup
  pinMode(PIN_OLED_SDA, INPUT_PULLUP);
  pinMode(PIN_OLED_SCL, INPUT_PULLUP);
  delay(100); // Allow display internal charge pump to stabilize after power-up

  // 2. Start Wire with 400kHz clock and 50ms timeout
  Wire.begin(PIN_OLED_SDA, PIN_OLED_SCL);
  Wire.setClock(400000);
  Wire.setTimeOut(50);

  // 3. Probe addresses (0x3C first, then 0x3D) with up to 4 retries
  const byte tryAddrs[] = { 0x3C, 0x3D };
  for (int attempt = 1; attempt <= 4; attempt++) {
    for (byte addr : tryAddrs) {
      Serial.printf("[OLED] Probing address 0x%02X (Attempt %d)...\n", addr, attempt);
      Wire.beginTransmission(addr);
      if (Wire.endTransmission() == 0) {
        if (display.begin(SSD1306_SWITCHCAPVCC, addr)) {
          oledAddress = addr;
          oledAvailable = true;
          Serial.printf("[OK] SSD1306 OLED initialized successfully at 0x%02X!\n", addr);
          display.clearDisplay();
          display.display();
          return true;
        }
      }
      delay(50);
    }
  }

  Serial.println("[WARN] OLED display not detected or failed to initialize.");
  Serial.println("[INFO] Station will continue running in headless mode (RFID + Cloud active).");
  oledAvailable = false;
  return false;
}

// ==========================================
// 7. CLOUD / SERVER COMMUNICATION (CORE 0)
// ==========================================

// Download batch roster for instantaneous local edge processing
void fetchSessionRoster(String sessionId) {
  if (WiFi.status() != WL_CONNECTED) return;

  HTTPClient http;
  WiFiClientSecure secClient;
  WiFiClient plainClient;

  String url = String(SERVER_URL) + "?device_id=" + String(DEVICE_ID) + "&action=roster";

  if (url.startsWith("https://")) {
    secClient.setInsecure();
    secClient.setTimeout(4);
    http.begin(secClient, url);
  } else {
    plainClient.setTimeout(4);
    http.begin(plainClient, url);
  }
  http.setTimeout(4000);

  int httpCode = http.GET();
  if (httpCode == 200) {
    String resString = http.getString();
    DynamicJsonDocument doc(8192);
    DeserializationError err = deserializeJson(doc, resString);
    if (!err && doc.containsKey("students")) {
      JsonArray students = doc["students"].as<JsonArray>();

      portENTER_CRITICAL(&rosterMux);
      localRosterCount = 0;
      for (JsonObject s : students) {
        if (localRosterCount >= MAX_CACHED_STUDENTS) break;
        const char* u = s["u"] | "";
        const char* i = s["i"] | "";
        bool p = s["p"] | false;
        if (strlen(u) > 0) {
          strncpy(localRoster[localRosterCount].uid, u, sizeof(localRoster[localRosterCount].uid) - 1);
          localRoster[localRosterCount].uid[sizeof(localRoster[localRosterCount].uid) - 1] = '\0';
          strncpy(localRoster[localRosterCount].studentId, i, sizeof(localRoster[localRosterCount].studentId) - 1);
          localRoster[localRosterCount].studentId[sizeof(localRoster[localRosterCount].studentId) - 1] = '\0';
          localRoster[localRosterCount].present = p;
          localRosterCount++;
        }
      }
      cachedSessionId = sessionId;
      portEXIT_CRITICAL(&rosterMux);

      Serial.printf("[EDGE-CACHE] Loaded %d students for session '%s' into local RAM!\n", (int)localRosterCount, sessionId.c_str());
    }
  }
  http.end();
}

// Device Status Heartbeat: Synchronizes station state with ClassMate server
void checkDeviceHeartbeat() {
  if (WiFi.status() != WL_CONNECTED) return;

  HTTPClient http;
  WiFiClientSecure secClient;
  WiFiClient plainClient;

  String url = String(SERVER_URL) + "?device_id=" + String(DEVICE_ID);

  if (url.startsWith("https://")) {
    secClient.setInsecure();
    secClient.setTimeout(3);
    http.begin(secClient, url);
  } else {
    plainClient.setTimeout(3);
    http.begin(plainClient, url);
  }
  http.setTimeout(3000);

  int httpCode = http.GET();
  if (httpCode == 200) {
    String resString = http.getString();
    StaticJsonDocument<384> doc;
    DeserializationError err = deserializeJson(doc, resString);
    if (!err) {
      const char* status = doc["status"] | "SLEEP";
      if (strcmp(status, "ACTIVE") == 0) {
        const char* code  = doc["course_code"] | "ATTENDANCE";
        const char* title = doc["course_title"] | "ClassMate Live Session";
        const char* sessId = doc["session_id"] | "";

        isDeviceSleeping = false;
        if (oledAvailable) {
          if (takeOledLock()) {
            display.dim(false); // Full brightness
            releaseOledLock();
          }
        }

        if (activeCourseCode != code || activeCourseTitle != title) {
          activeCourseCode  = String(code);
          activeCourseTitle = String(title);
          marqueeX = 128;
        }

        currentScreenMode = SCREEN_COURSE_ACTIVE;

        // Automatically fetch local roster cache if session changed
        if (strlen(sessId) > 0 && cachedSessionId != sessId) {
          fetchSessionRoster(String(sessId));
        }
      } else {
        // Status is SLEEP / NO_ACTIVE_SESSION -> Go to clean Standby/Idle Screen
        // (DO NOT SHUT OFF DISPLAY! Keep it visible so user knows power is ON)
        if (!isDeviceSleeping) {
          isDeviceSleeping = true;
          currentScreenMode = SCREEN_IDLE;
          screenNeedsRedraw = true;
          if (oledAvailable) {
            if (takeOledLock()) {
              display.dim(true); // Dim slightly to protect OLED from burn-in while remaining readable
              releaseOledLock();
            }
          }
          digitalWrite(PIN_LED_GREEN, LOW);
          digitalWrite(PIN_LED_RED, LOW);
        }
        // Reset presence state when session ends, but KEEP student ID mapping
        if (cachedSessionId.length() > 0) {
          portENTER_CRITICAL(&rosterMux);
          cachedSessionId = "";
          for (size_t i = 0; i < localRosterCount; i++) {
            localRoster[i].present = false;
          }
          portEXIT_CRITICAL(&rosterMux);
        }
        // If roster not loaded yet, fetch master roster so Student IDs are ready
        if (localRosterCount == 0) {
          fetchSessionRoster("");
        }
      }
    }
  }
  http.end();
}

// Upload a batch of card scans in a SINGLE high-speed HTTPS request (Background Core 0)
bool uploadScanBatchToCloud(char batchUids[][12], int count) {
  if (WiFi.status() != WL_CONNECTED || count <= 0) return false;

  HTTPClient http;
  WiFiClientSecure secClient;
  WiFiClient plainClient;

  if (strncmp(SERVER_URL, "https://", 8) == 0) {
    secClient.setInsecure();
    secClient.setTimeout(5);
    http.begin(secClient, SERVER_URL);
  } else {
    plainClient.setTimeout(5);
    http.begin(plainClient, SERVER_URL);
  }
  http.addHeader("Content-Type", "application/json");
  http.setTimeout(5000);

  // Dynamic JSON doc for batch payload
  DynamicJsonDocument doc(2048);
  doc["device_id"] = DEVICE_ID;
  JsonArray batchArr = doc.createNestedArray("batch");
  for (int i = 0; i < count; i++) {
    batchArr.add(batchUids[i]);
  }

  String reqJson;
  serializeJson(doc, reqJson);

  int httpCode = http.POST(reqJson);
  bool success = false;
  if (httpCode == 200) {
    String resString = http.getString();
    Serial.printf("[BATCH SYNC] Successfully uploaded %d scans -> HTTP 200: %s\n", count, resString.c_str());
    success = true;
  } else {
    Serial.printf("[BATCH SYNC] HTTP POST failed for %d scans (code %d)\n", count, httpCode);
  }
  http.end();
  return success;
}

// Single scan fallback wrapper
bool uploadScanToCloud(const char* uid) {
  char tempArr[1][12];
  strncpy(tempArr[0], uid, sizeof(tempArr[0]) - 1);
  tempArr[0][sizeof(tempArr[0]) - 1] = '\0';
  return uploadScanBatchToCloud(tempArr, 1);
}

// -------------------------------------------------------------
// MULTI-WIFI SEQUENTIAL ENGINE
// -------------------------------------------------------------
int currentWifiIndex = 0;

// Connect to a specific configured Wi-Fi network directly
bool connectToWiFiNetwork(int index, unsigned long timeoutMs = 6000, bool showConnectingOled = false) {
  if (index < 0 || index >= (int)WIFI_NETWORK_COUNT) index = 0;
  currentWifiIndex = index;

  const char* ssid = CONFIGURED_WIFIS[index].ssid;
  const char* pass = CONFIGURED_WIFIS[index].password;

  Serial.printf("\n[WIFI] Trying [%d/%d]: '%s' for %lu seconds...\n", 
                index + 1, (int)WIFI_NETWORK_COUNT, ssid, timeoutMs / 1000);

  // Render connecting banner safely only if requested (e.g. during initial boot setup)
  if (showConnectingOled && oledAvailable && takeOledLock()) {
    display.clearDisplay();
    display.setTextColor(SSD1306_WHITE, SSD1306_BLACK);
    display.setTextSize(1);
    display.setCursor(10, 16);
    display.printf("TRYING WIFI [%d/%d]", index + 1, (int)WIFI_NETWORK_COUNT);
    display.drawLine(0, 26, 127, 26, SSD1306_WHITE);
    display.setCursor(10, 36);
    display.println(ssid);
    display.setCursor(10, 50);
    display.println("Connecting...");
    display.display();
    releaseOledLock();
  }

  WiFi.disconnect();
  delay(50);
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, pass);

  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED && (millis() - start < timeoutMs)) {
    delay(250);
    Serial.print(".");
  }

  if (WiFi.status() == WL_CONNECTED) {
    connectedWifiSsid = WiFi.SSID();
    Serial.printf("\n[OK] Connected to '%s'! IP: %s\n", 
                  connectedWifiSsid.c_str(), WiFi.localIP().toString().c_str());

    if (showConnectingOled && oledAvailable && takeOledLock()) {
      display.clearDisplay();
      display.setCursor(16, 18);
      display.println("WIFI CONNECTED!");
      display.setCursor(16, 32);
      display.println(connectedWifiSsid);
      display.setCursor(16, 46);
      display.println(WiFi.localIP().toString());
      display.display();
      releaseOledLock();
      soundInstantTap();
      delay(300);
    }
    return true;
  }

  Serial.printf("\n[WIFI] '%s' failed after %lu seconds.\n", ssid, timeoutMs / 1000);
  return false;
}

// Quick Next-Network Fallback for backgroundSyncTask (Core 0, non-disruptive to active OLED)
bool switchToNextWiFi(unsigned long timeoutMs = 5000) {
  if (WiFi.status() == WL_CONNECTED) return true;
  currentWifiIndex = (currentWifiIndex + 1) % WIFI_NETWORK_COUNT;
  return connectToWiFiNetwork(currentWifiIndex, timeoutMs, false);
}

// ------------------------------------------
// CORE 0 ASYNCHRONOUS WORKER TASK
// ------------------------------------------
void backgroundSyncTask(void* pvParameters) {
  char batchUids[BATCH_UPLOAD_SIZE][12];

  for (;;) {
    // 1. Process pending scan uploads in high-speed batches
    int countToUpload = 0;
    if (WiFi.status() == WL_CONNECTED && pendingScanCount > 0) {
      if (syncMutex != NULL && xSemaphoreTake(syncMutex, pdMS_TO_TICKS(50)) == pdTRUE) {
        countToUpload = pendingScanCount;
        if (countToUpload > BATCH_UPLOAD_SIZE) countToUpload = BATCH_UPLOAD_SIZE;
        for (int i = 0; i < countToUpload; i++) {
          strncpy(batchUids[i], pendingScans[i].cardUid, sizeof(batchUids[0]));
        }
        xSemaphoreGive(syncMutex);
      }

      if (countToUpload > 0) {
        bool uploaded = uploadScanBatchToCloud(batchUids, countToUpload);
        if (uploaded) {
          // ATOMIC CLEAR: Only remove scans after confirmed server HTTP 200 BATCH_OK
          if (syncMutex != NULL && xSemaphoreTake(syncMutex, pdMS_TO_TICKS(100)) == pdTRUE) {
            for (int i = 0; i < pendingScanCount - countToUpload; i++) {
              pendingScans[i] = pendingScans[i + countToUpload];
            }
            pendingScanCount -= countToUpload;
            xSemaphoreGive(syncMutex);
          }
          savePendingScansToFlash();
          Serial.printf("[SYNC] Upload confirmed! Remaining in queue: %d\n", pendingScanCount);
        } else {
          // If upload fails, DO NOT DROP! Scans remain safely in RAM & Flash.
          Serial.printf("[SYNC] Batch upload failed. Scans preserved in Flash. Retrying shortly...\n");
          vTaskDelay(pdMS_TO_TICKS(1500));
        }
      }
    }

    // 2. Runtime fallback check if Wi-Fi dropped
    static unsigned long lastWifiCheck = 0;
    if (WiFi.status() != WL_CONNECTED) {
      if (millis() - lastWifiCheck > 5000) {
        lastWifiCheck = millis();
        Serial.println("[WIFI] Wi-Fi down! Trying next configured network...");
        if (switchToNextWiFi(5000)) {
          Serial.printf("[WIFI] Reconnected: %s\n", connectedWifiSsid.c_str());
          checkDeviceHeartbeat();
        }
      }
    }

    // 3. Heartbeat & Roster Sync (Every 4s active, 8s standby)
    static unsigned long lastBgHeartbeat = 0;
    unsigned long hbInterval = isDeviceSleeping ? 8000 : 4000;
    if (millis() - lastBgHeartbeat > hbInterval) {
      lastBgHeartbeat = millis();
      if (WiFi.status() == WL_CONNECTED) {
        checkDeviceHeartbeat();
      }
    }

    vTaskDelay(pdMS_TO_TICKS(30));
  }
}

// ==========================================
// 8. SETUP
// ==========================================
void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("\n\n==========================================");
  Serial.println("  ClassMate Attendance Station (High-Speed Edge Edition)");
  Serial.println("==========================================");

  // Initialize Mutexes for Thread Safety
  oledMutex = xSemaphoreCreateMutex();
  syncMutex = xSemaphoreCreateMutex();

  // Load any unsynced offline scans from Non-Volatile Flash (NVS)
  loadPendingScansFromFlash();

  // Pin Configuration
  pinMode(PIN_BUZZER, OUTPUT);
  pinMode(PIN_LED_GREEN, OUTPUT);
  pinMode(PIN_LED_RED, OUTPUT);
  digitalWrite(PIN_BUZZER, LOW);
  digitalWrite(PIN_LED_GREEN, LOW);
  digitalWrite(PIN_LED_RED, LOW);

  // Initialize I2C OLED with clean bus recovery and multi-address probing
  if (initOLEDDisplay()) {
    if (takeOledLock()) {
      display.clearDisplay();
      display.setTextColor(SSD1306_WHITE);
      display.setTextSize(1);
      display.setCursor(18, 14);
      display.println("CLASSMATE SYSTEM");
      display.setCursor(14, 28);
      display.println("Edge Station Ready");
      display.setCursor(16, 44);
      display.println("Initializing...");
      display.display();
      releaseOledLock();
    }
  }

  // Initialize SPI & RC522 RFID Reader
  SPI.begin(PIN_SPI_SCK, PIN_SPI_MISO, PIN_SPI_MOSI, PIN_SPI_SS);
  rfid.PCD_Init();
  delay(50);

  byte mfrcVer = rfid.PCD_ReadRegister(MFRC522::VersionReg);
  Serial.printf("[HARDWARE] RC522 Reader Firmware Version: 0x%02X\n", mfrcVer);
  if (mfrcVer == 0x00 || mfrcVer == 0xFF) {
    Serial.println("[WARN] RC522 Reader communication check returned unexpected version.");
  } else {
    Serial.println("[OK] RC522 RFID Reader successfully online.");
  }

  // Setup Multi-WiFi Fallback Network List
  Serial.println("\n[WIFI] Configured fallback networks:");
  for (size_t i = 0; i < WIFI_NETWORK_COUNT; i++) {
    Serial.printf("  [%d] '%s' (%s)\n", (int)i + 1, CONFIGURED_WIFIS[i].ssid, CONFIGURED_WIFIS[i].label);
  }

  // Connect to Wi-Fi (Non-blocking loop: tries each network for 6s once with OLED feedback)
  Serial.println("\n[WIFI] Checking available Wi-Fi networks...");
  bool wifiConnected = false;
  for (size_t i = 0; i < WIFI_NETWORK_COUNT; i++) {
    if (connectToWiFiNetwork(i, 6000, true)) {
      wifiConnected = true;
      break;
    }
  }

  if (!wifiConnected) {
    Serial.println("[WIFI] None of the configured networks in range. Booting in Offline Standby Mode.");
    connectedWifiSsid = "Offline Mode";
  }

  // Pre-cache enrolled students if online
  if (WiFi.status() == WL_CONNECTED) {
    fetchSessionRoster("");
  }

  // Launch Core 0 Background Worker Task with 12KB stack
  xTaskCreatePinnedToCore(
    backgroundSyncTask,
    "SyncWorker",
    12288,
    NULL,
    1,
    &syncTaskHandle,
    0   // Core 0 (leaving Core 1 free for instant RFID & OLED)
  );
  Serial.println("[SYSTEM] Core 0 Background Sync Task Launched!");

  // Perform initial session check if online
  if (WiFi.status() == WL_CONNECTED) {
    checkDeviceHeartbeat();
  }

  // Initial draw
  currentScreenMode = SCREEN_IDLE;
  screenNeedsRedraw = true;
}

// ==========================================
// 9. MAIN LOOP (CORE 1: INSTANT RFID & OLED)
// ==========================================
void loop() {
  unsigned long now = millis();

  // RC522 Reader Hardware Health Watchdog (Every 30s)
  if (now - lastRfidHealthCheck > 30000) {
    lastRfidHealthCheck = now;
    byte ver = rfid.PCD_ReadRegister(MFRC522::VersionReg);
    if (ver == 0x00 || ver == 0xFF) {
      Serial.println("[WATCHDOG] RC522 SPI bus lost. Reinitializing PCD...");
      rfid.PCD_Init();
    }
  }

  // Screen Rendering (Core 1 Ownership)
  if (currentScreenMode == SCREEN_COURSE_ACTIVE) {
    if (now - lastMarqueeTime > 35) {
      lastMarqueeTime = now;
      drawActiveCourseScreen();
    }
  } else if (currentScreenMode == SCREEN_IDLE) {
    // Redraw idle screen if requested or every 4 seconds to update Wi-Fi status
    if (screenNeedsRedraw || (now - lastIdleRedraw > 4000)) {
      lastIdleRedraw = now;
      drawBaseIdleScreen();
      screenNeedsRedraw = false;
    }
  }

  // Check for RFID card presence (Instant polling)
  if (!rfid.PICC_IsNewCardPresent()) {
    return;
  }

  if (!rfid.PICC_ReadCardSerial()) {
    return;
  }

  // Convert UID to clean uppercase hex string without delimiters (e.g. "03E1AA05")
  String cardUid = "";
  for (byte i = 0; i < rfid.uid.size; i++) {
    if (rfid.uid.uidByte[i] < 0x10) cardUid += "0";
    cardUid += String(rfid.uid.uidByte[i], HEX);
  }
  cardUid.toUpperCase();

  Serial.println("\n==========================================");
  Serial.printf(">>> CARD TAPPED! UID: %s <<<\n", cardUid.c_str());
  Serial.println("==========================================");

  // Debounce duplicate rapid touches of the exact same physical card
  if (cardUid == lastUid && (now - lastScanTime < COOLDOWN_MS)) {
    Serial.printf("[RFID] Cooldown active (%lums remaining). Ignored.\n", COOLDOWN_MS - (now - lastScanTime));
    rfid.PICC_HaltA();
    rfid.PCD_StopCrypto1();
    return;
  }

  lastUid = cardUid;
  lastScanTime = now;

  // Instant Tap Feedback Sound & Green Flash
  digitalWrite(PIN_LED_GREEN, HIGH);
  soundInstantTap();
  delay(30);
  digitalWrite(PIN_LED_GREEN, LOW);

  // Wake display if dimmed
  if (oledAvailable && takeOledLock()) {
    display.dim(false);
    releaseOledLock();
  }

  // =========================================================================
  // CHECK IF SESSION IS ACTIVE:
  // If no attendance session is running or started in app/web:
  // Show "ATTENDANCE NOT STARTED" and return display to clean Standby Screen!
  // =========================================================================
  if (currentScreenMode != SCREEN_COURSE_ACTIVE) {
    // Look up student ID in local cache
    int studentIdx = -1;
    char knownId[16] = {0};
    portENTER_CRITICAL(&rosterMux);
    for (size_t i = 0; i < localRosterCount; i++) {
      if (strcmp(localRoster[i].uid, cardUid.c_str()) == 0) {
        studentIdx = (int)i;
        strncpy(knownId, localRoster[i].studentId, sizeof(knownId) - 1);
        break;
      }
    }
    portEXIT_CRITICAL(&rosterMux);

    // If not cached yet (e.g. freshly booted), query server roster once if online
    if (studentIdx < 0 && WiFi.status() == WL_CONNECTED) {
      fetchSessionRoster("");
      portENTER_CRITICAL(&rosterMux);
      for (size_t i = 0; i < localRosterCount; i++) {
        if (strcmp(localRoster[i].uid, cardUid.c_str()) == 0) {
          studentIdx = (int)i;
          strncpy(knownId, localRoster[i].studentId, sizeof(knownId) - 1);
          break;
        }
      }
      portEXIT_CRITICAL(&rosterMux);
    }

    // Display "Attendance Not Started" screen with genuine Student ID!
    bool isReg = (studentIdx >= 0 && strlen(knownId) > 0);
    showAttendanceNotStartedScreen(isReg ? knownId : NULL, isReg);

    // Audio & Red LED alert
    digitalWrite(PIN_LED_RED, HIGH);
    soundBeep(1000, 90);
    delay(50);
    soundBeep(750, 120);
    digitalWrite(PIN_LED_RED, LOW);

    delay(1800); // Allow student to read the message

    // Return cleanly to IDLE Standby screen (DO NOT SHUT OFF DISPLAY)
    currentScreenMode = SCREEN_IDLE;
    screenNeedsRedraw = true;
    if (oledAvailable && takeOledLock()) {
      display.dim(true);
      releaseOledLock();
    }

    rfid.PICC_HaltA();
    rfid.PCD_StopCrypto1();
    return;
  }

  // ----------------------------------------------------
  // INSTANT LOCAL EDGE LOOKUP (< 0.001ms RAM Search!)
  // ----------------------------------------------------
  int studentIdx = -1;
  char matchedStudentId[16] = {0};
  bool alreadyMarked = false;

  portENTER_CRITICAL(&rosterMux);
  for (size_t i = 0; i < localRosterCount; i++) {
    if (strcmp(localRoster[i].uid, cardUid.c_str()) == 0) {
      studentIdx = (int)i;
      strncpy(matchedStudentId, localRoster[i].studentId, sizeof(matchedStudentId) - 1);
      alreadyMarked = localRoster[i].present;
      if (!alreadyMarked) {
        localRoster[i].present = true; // Mark present locally immediately
      }
      break;
    }
  }
  portEXIT_CRITICAL(&rosterMux);

  if (studentIdx >= 0) {
    if (!alreadyMarked) {
      // ==========================================
      // FRESH FIRST TAP: INSTANT PRESENT (< 50ms)
      // ==========================================
      // 1. Enqueue for background upload and persist immediately to NVS Flash
      enqueuePendingScan(cardUid.c_str(), now);

      // 2. Instantly show ONLY Student ID (260ms)
      showOnlyStudentId(matchedStudentId);
      delay(260);

      // 3. Instantly transition to PRESENT tick animation + Chime (500ms)
      digitalWrite(PIN_LED_GREEN, HIGH);
      showCheckmarkTickAnimation();
      soundPresentChime();
      delay(500);
      digitalWrite(PIN_LED_GREEN, LOW);

      // 4. Restore active course screen immediately
      currentScreenMode = SCREEN_COURSE_ACTIVE;
      marqueeX = 128;
    } else {
      // ==========================================
      // DOUBLE TAP: ALREADY PRESENT (LOCAL REJECT)
      // ==========================================
      // 1. Instantly show ONLY Student ID (260ms)
      showOnlyStudentId(matchedStudentId);
      delay(260);

      // 2. Show Cross (X) sign animation with "ALREADY PRESENTED"
      showCrossAlreadyAnimation();

      // 3. Red LED blinks twice with alert beeps
      for (int i = 0; i < 2; i++) {
        digitalWrite(PIN_LED_RED, HIGH);
        soundBeep(900, 90);
        digitalWrite(PIN_LED_RED, LOW);
        delay(100);
      }
      delay(350);

      // 4. Restore active course screen immediately
      currentScreenMode = SCREEN_COURSE_ACTIVE;
      marqueeX = 128;
    }
  } else {
    // ==========================================
    // UNCACHED / UNREGISTERED CARD FALLBACK
    // ==========================================
    enqueuePendingScan(cardUid.c_str(), now);

    showWarningScreen("NOT REGISTERED", "Card UID Unknown", cardUid);
    soundWarningBeeps();
    delay(750);
    currentScreenMode = SCREEN_COURSE_ACTIVE;
    marqueeX = 128;
  }

  // Complete RFID card transaction
  rfid.PICC_HaltA();
  rfid.PCD_StopCrypto1();
}
