# Phone Robot

An offline-first robot head framework built for Android smartphones (tested and optimized for mid-range hardware like the **Huawei Mate 10 Lite**), featuring local face tracking, local person recognition, offline voice input and speech synthesis, an animated reactive robot face, and an optional server bridge for conversational AI with any LLM provider.

---

## Architecture Overview

```
                        ┌─────────────────────────────────────────┐
                        │              Android Phone              │
                        │                                         │
 ┌───────────────┐      │  ┌───────────────────────────────────┐  │
 │ Front Camera  ├─────►│  │ RobotVision (ML Kit + TFLite)     │  │
 └───────────────┘      │  │ • Local face tracking             │  │
                        │  │ • Enrolled identity recognition   │  │
                        │  └─────────────────┬─────────────────┘  │
                        │                    │ Face coordinates   │
 ┌───────────────┐      │  ┌─────────────────▼─────────────────┐  │
 │ Microphone    ├─────►│  │ RobotSpeech (Offline Vosk STT)    │  │
 └───────────────┘      │  │ • Local audio capture             │  │
                        │  └─────────────────┬─────────────────┘  │
                        │                    │ Transcribed text   │
                        │  ┌─────────────────▼─────────────────┐  │      ┌────────────────────────┐
                        │  │ CommandRouter                     │  │      │   Robot Backend (Py)   │
                        │  │ • Offline local commands          │  │      │                        │
                        │  │ • Cloud conversation gate         ├─(HTTPS)►│ Any LLM Provider:      │
                        │  └─────────────────┬─────────────────┘  │      │ • OpenRouter (Free)    │
                        │                    │ Reply text         │      │ • Ollama (Local free)  │
 ┌───────────────┐      │  ┌─────────────────▼─────────────────┐  │      │ • Groq, OpenAI, etc.   │
 │ Phone Speaker ◄──────┼──┤ Offline Android TTS               │  │      └────────────────────────┘
 └───────────────┘      │  │ • Local voice response            │  │
                        │  └───────────────────────────────────┘  │
                        │  ┌───────────────────────────────────┐  │
 ┌───────────────┐      │  │ RobotFaceView & Screen            │  │
 │ Screen Eyes   │◄─────┼──┤ • Animated tracking eyes          │  │
 │ & Mouth       │      │  │ • Mode: Ready, Speaking, Sleeping │  │
 └───────────────┘      │  └─────────────────┬─────────────────┘  │
                        │                    │ Bounded targets    │
                        │                    ▼ [-1.0, 1.0]        │
                        │          RobotHardware Interface        │
                        │          (Future ESP32 / Servos)        │
                        └─────────────────────────────────────────┘
```

---

## Target Phone Reference: Huawei Mate 10 Lite

* **Processor & Memory:** HiSilicon Kirin 659 octa-core, 4 GB RAM, 64 GB storage.
* **Display:** 5.9-inch IPS LCD (1080 × 2160 pixels, 18:9 aspect ratio).
* **Weight:** 164 grams (phone body only). Keep this weight in mind when selecting neck servos (MG996R or high-torque metal gear servos recommended) and calculating balance/counterweights.
* **Operating System:** Android 7.0 / 8.0 (API 24 minimum, target API 34).
* **Sensors:** Accelerometer, battery monitor, front dual cameras.

---

## 1. Android App Setup

### Prerequisites
* Java Development Kit (JDK 17)
* Android SDK (Platforms 24–36, Build-Tools 34+)
* Python 3 (for running model setup scripts)

### Step 1: Download Offline Model Assets
The app uses on-device models for privacy and zero-latency offline operation:

```bash
# 1. Fetch offline speech recognition model (Vosk small English model, ~70MB)
python3 tools/fetch_speech_model.py

# 2. Fetch MobileFaceNet embedding model (~5MB)
python3 tools/fetch_face_model.py --acknowledge-model-license
```

These scripts verify SHA-256 checksums and install the models into `app/src/main/assets/`. The binaries are ignored by git to keep the repository clean.

### Step 2: Build the Android App

```bash
# Build the debug APK and run unit tests
./gradlew :app:assembleDebug :app:testDebugUnitTest :core:test
```

The output APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

### Step 3: Install on Your Phone

Connect your phone via USB with USB Debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 2. Using the Robot

When you open **Phone Robot** on the phone:

1. **Grant Permissions:** Allow Camera (for face tracking) and Microphone (for offline voice input).
2. **Offline Local Voice Commands:**
   * `track me` / `follow my face` — Starts following your face on screen.
   * `look left`, `look right`, `look up`, `look down`, `look center` — Moves eyes on screen.
   * `stop` / `emergency stop` — Halts tracking, silences speech, and centers eyes.
   * `sleep` — Puts the robot to sleep and turns off the camera to save battery.
   * `wake up` — Wakes the robot up.
   * `hello` / `hi` — Friendly offline greeting.
   * `time` / `what time is it` — Tells current device time.
   * `battery` — Reports battery percentage.
   * `help` — Lists available commands.
3. **Enrolling Consenting People:**
   * Tap **People** on screen.
   * Look directly at the front camera in good lighting.
   * Enter the person's name and tap **Consent & enroll**.
   * Only normalized 192-dimensional embeddings are saved locally; no camera photos are ever stored or uploaded.
   * To delete an enrolled person, tap **People** and select their name to delete their local template.

---

## 3. Optional Conversational AI Backend

When you ask an open-ended conversational question (something other than the local commands above), the app can forward the text question to an optional backend server.

The server is **provider-agnostic** and works with **any LLM**:
* **Free tier OpenRouter** (`meta-llama/llama-3.3-70b-instruct:free`, `google/gemini-2.0-flash-lite-preview-02-05:free`, etc.)
* **100% Free Local Ollama** (running offline on your laptop/computer)
* **Groq, DeepInfra, Together, OpenAI, Anthropic**, etc.

### Setting Up the Server

1. Navigate to `server/`:
   ```bash
   cd server
   python3 -m venv .venv
   source .venv/bin/activate
   pip install -r requirements.txt
   ```

2. Configure environment:
   ```bash
   cp .env.example .env
   ```

3. Edit `server/.env` with your choice of provider:

   **Option A: OpenRouter (Recommended for Free Cloud AI)**
   ```env
   AI_BASE_URL=https://openrouter.ai/api/v1
   AI_MODEL=meta-llama/llama-3.3-70b-instruct:free
   AI_API_KEY=sk-or-v1-your-openrouter-key
   ROBOT_API_TOKEN=generate-a-random-32-character-secret-token-here
   ```

   **Option B: 100% Free Local Ollama (Zero Cloud Spend)**
   ```env
   AI_BASE_URL=http://127.0.0.1:11434/v1
   AI_MODEL=llama3.2
   AI_API_KEY=
   ROBOT_API_TOKEN=generate-a-random-32-character-secret-token-here
   ```

   **Option C: Groq (Ultra-fast Free Tier)**
   ```env
   AI_BASE_URL=https://api.groq.com/openai/v1
   AI_MODEL=llama-3.1-8b-instant
   AI_API_KEY=gsk_your-groq-key
   ROBOT_API_TOKEN=generate-a-random-32-character-secret-token-here
   ```

4. Run the server:
   ```bash
   python -m server
   ```
   The service listens locally on `http://127.0.0.1:8000`.

5. Run server test suite:
   ```bash
   pytest tests/ -v
   ```

### Connecting the Android App to Your Server

The Android app requires an **HTTPS** endpoint:
1. Expose your server via HTTPS using a reverse proxy or tunnel:
   * [Cloudflare Tunnels](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/) (free)
   * [Tailscale Funnel](https://tailscale.com/kb/1223/funnel) (free)
   * [ngrok](https://ngrok.com/) (`ngrok http 8000`)
2. In the app on the phone, tap **AI settings**:
   * Check **Allow online AI for unknown requests**.
   * Enter your HTTPS backend URL (e.g. `https://my-robot.example.com`).
   * Enter your `ROBOT_API_TOKEN` (must match the token in `server/.env`).
   * Tap **Save**. Settings are stored encrypted in the Android KeyStore.

---

## 4. Hardware & Servo Boundary

The framework includes a decoupled `RobotHardware` boundary in `core/src/main/kotlin/dev/phonerobot/core/MotionTarget.kt`:

```kotlin
data class MotionTarget(val x: Float, val y: Float) // Coordinates clamped to [-1.0, 1.0]

interface RobotHardware {
    fun look(target: MotionTarget)
    fun stop()
}
```

* **Safety Isolation:** Conversation text from AI providers **never** controls hardware directly.
* **Coordination:** Camera face tracking passes normalized coordinates through a safety `MotionGate` that clamps targets to `[-1.0, 1.0]`.
* **Actuator Integration:** When connecting an ESP32 or Arduino over USB-OTG/Bluetooth to drive neck or mouth servos:
  1. Implement `RobotHardware` to translate `[-1.0, 1.0]` targets into PWM servo angles.
  2. Implement servo acceleration curves and mechanical limit switches in the microcontroller.

---

## Privacy & Security

* **Camera & Biometrics:** Camera video frames and facial embedding vectors never leave the phone. Stored templates are stored in private app storage with backup disabled.
* **Microphone & Audio:** Speech recognition runs 100% on-device via Vosk. No raw audio is ever recorded or uploaded.
* **Cloud Transmissions:** Only typed messages or finalized local speech transcripts are sent to the AI backend when online AI is enabled.
* **Token Protection:** Device tokens are stored using AES-GCM encrypted in the Android KeyStore. Server requests use constant-time digest comparison to prevent timing leaks.
