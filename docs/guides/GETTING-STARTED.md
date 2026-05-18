# Borizon — Getting Started

**Build, run, and develop Borizon on your machine.**

---

## Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| Android Studio | Hedgehog (2023.1.1)+ | Or any recent version |
| JDK | 17 | Required by Kotlin 2.0+ |
| Android SDK | API 35 (Android 15) | Compile and target SDK |
| Min SDK | API 28 (Android 9) | Minimum supported device |
| Physical device | 8 GB+ RAM | Recommended for model inference |
| Git | Any | For cloning |

> **Note:** The emulator does not support LiteRT-LM GPU acceleration. Use a physical device for the full experience.

---

## Clone & Build

```bash
git clone https://github.com/stawils/borizon.git
cd borizon

# Build debug APK
./gradlew assembleDebug

# Build and install on connected device
./gradlew installDebug
```

The first build may take a few minutes for Gradle sync and KSP code generation.

---

## First Launch Flow

1. **Splash** → Welcome screen
2. **Onboarding** — Choose model (E2B or E4B), optionally share your name and preferences
3. **Model download** — Downloads from HuggingFace (~2.6 GB for E2B, ~3.7 GB for E4B)
4. **Biometric setup** — Set up biometric lock (mandatory)
5. **Chat** — Start talking to Borizon

After model download, the app works fully offline.

---

## Model Selection

| Model | Download Size | Min RAM | Best For |
|-------|--------------|---------|----------|
| **Gemma 4 E2B** | ~2.6 GB | 6 GB | Mid-range phones, faster responses |
| **Gemma 4 E4B** | ~3.7 GB | 8 GB | Better quality, more tools (default) |

Switch models anytime in Settings. The new model downloads automatically.

### How models differ

- **E2B** gets 13 core tools (smaller KV cache budget)
- **E4B** gets all 14 tools including skills
- **E4B** produces higher-quality responses and handles multi-step tool calls better

---

## Project Structure

```
borizon/
├── app/src/main/java/com/borizon/app/
│   ├── ai/              # AI pipeline: agents, inference, tools, prompts
│   ├── ui/              # Screens, components, theme, navigation
│   ├── data/            # Models, DAOs, database, preferences
│   ├── audio/           # Speech-to-text, audio recording, TTS
│   ├── skills/          # Skill manager and JS bridge
│   ├── di/              # Hilt dependency injection
│   └── util/            # Extensions, bitmap utilities
├── app/src/main/assets/
│   └── skills/          # Built-in skills (5: morning-briefing, phone-status, study-buddy, wellness-check, travel-helper)
├── docs/                # Documentation (this folder)
├── plans/               # Development plans and audit reports
├── build.gradle.kts     # App-level build configuration
└── settings.gradle.kts  # Project settings
```

### Key Files

| File | Role |
|------|------|
| `BorizonViewModel.kt` | Central ViewModel — owns agents, tools, state, message flow |
| `ReflectAgent.kt` | Conversation handler — agent loop, tool orchestration, memory injection |
| `LiteRTInferenceEngine.kt` | Real LiteRT-LM backend with tool-calling |
| `ModelManager.kt` | Engine lifecycle, adaptive KV cache, GPU/CPU fallback |
| `AgentSystemPrompt.kt` | System prompt with IF-THEN tool triggers |
| `ChatScreen.kt` | Main chat UI — messages, streaming, voice, multimodal |
| `ContextCompactor.kt` | Proactive context compression at 60% budget |
| `BorizonDatabase.kt` | Room + SQLCipher + Android Keystore key management |
| `MemoryDao.kt` | Time-decay memory ranking and access tracking |
| `ShellSandbox.kt` | Sandboxed shell execution (safe mode + shell mode) |

---

## Configuration

### Brave Search API (Optional)

Web search requires a Brave Search API key:

1. Get a free key at [brave.com/search/api](https://brave.com/search/api/)
2. Open Borizon → Settings → Brave Search API Key
3. Paste your key

Without this key, web search tools are unavailable. All other tools work offline.

### Model Parameters

Advanced model settings available via Model Config sheet (accessible from chat):

| Parameter | Default | Range | Notes |
|-----------|---------|-------|-------|
| Temperature | 0.7 | 0.0–2.0 | Higher = more creative |
| Top-K | 40 | 1–100 | Token sampling pool |
| Max tokens | Auto | 4096–16384 | Scales with device RAM |

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| AI Model | Gemma 4 E2B/E4B via LiteRT-LM |
| Tool-Calling | LiteRT native `automaticToolCalling` |
| Database | Room (SQLite) + SQLCipher encryption |
| DI | Hilt + KSP |
| Preferences | Proto DataStore |
| Markdown | halilibo richtext (CommonMark) |
| Audio | Android SpeechRecognizer + custom WAV recorder |
| Web Search | Brave Search API via OkHttp |
| Navigation | Jetpack Navigation Compose |
| Background Work | WorkManager (model downloads) |
| Camera | CameraX |

---

## Build Variants

| Variant | Minification | Debugging | Notes |
|---------|-------------|-----------|-------|
| **Debug** | No | Full | Default for development |
| **Release** | R8 + ProGuard | Stripped | Smaller APK, harder to reverse-engineer |

```bash
./gradlew assembleDebug    # Debug APK
./gradlew assembleRelease  # Release APK (requires signing config)
```

---

## Development Tips

### No Physical Device?

Borizon requires LiteRT-LM which only runs on real hardware (ARM/ARM64). The emulator won't work for inference testing. However:

- Build verification works on any machine (`./gradlew compileDebugKotlin`)
- UI layout testing works in the emulator (Compose Preview)
- All non-inference code (data layer, tools, skills) works without the model

### Adding a New Tool

1. Create a new `FooTools.kt` in `ai/tools/`
2. Implement `ToolSet` interface with `@Tool`-annotated methods
3. Register in `BorizonViewModel` (add to `coreTools` or `extendedTools`)
4. Add a `ToolType` entry in `BorizonActions.kt` for UI feedback
5. Keep tool descriptions under 15 words (token budget)
6. Run on IO dispatcher for any I/O

### Adding a New Skill

1. Create a folder under `app/src/main/assets/skills/my-skill/`
2. Add `SKILL.md` with frontmatter (name, description, trigger phrases)
3. Write instructions in the body — these are injected into the prompt when a trigger matches
4. Skills orchestrate existing tools (memorySave, shellExecute, etc.) — no new code needed
5. Optionally add `scripts/main.js` for JS-based execution via WebView
6. Test on device

---

## Troubleshooting

| Issue | Solution |
|-------|---------|
| Build fails with KSP errors | Clean build: `./gradlew clean assembleDebug` |
| Model download fails | Check internet connection. Re-download from Settings. |
| App crashes on launch | Ensure device has ≥8 GB RAM. Try E2B model. |
| GPU inference crashes | Auto-fallback to CPU. Known issue on some MediaTek chips. |
| Tools not responding | Check runtime permissions in Android Settings → Apps → Borizon. |
| Notifications not captured | Enable notification access in Settings → Apps → Special access. |
