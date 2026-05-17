# Borizon — Architecture

**System architecture for Borizon, a private on-device AI companion.**

---

## System Overview

```
┌──────────────────────────────────────────────────────────┐
│                       UI Layer                           │
│  ChatScreen ← BorizonViewModel ← ReflectAgent           │
│       │              │              │                    │
│  [BorizonAction]  [StateFlow]   [actionChannel]         │
└───────┬──────────────┬──────────────┬───────────────────┘
        │              │              │
        ▼              ▼              ▼
┌──────────────────────────────────────────────────────────┐
│                      AI Pipeline                         │
│                                                          │
│  User Message                                            │
│       │                                                  │
│       ▼                                                  │
│  ContextCompactor (proactive, 60% threshold)             │
│       │                                                  │
│       ▼                                                  │
│  ReflectAgent (Gemma 4 via LiteRT-LM)                    │
│  ├── Inject top-8 relevant memories from MemoryDao       │
│  ├── Build system prompt (AgentSystemPrompt)             │
│  ├── Run Pi-style ReAct loop (max 3 iterations)         │
│  │   ├── ToolCallTracker.reset()                        │
│  │   ├── runAgentTurn() → LiteRT automaticToolCalling   │
│  │   ├── Check: tools called? ack? silent?              │
│  │   └── Force turn if model acknowledged without acting│
│  ├── Stream tokens to UI
│  └── Stream tokens to UI                                 │
│       │                                                  │
│       ▼                                                  │
│  14 @Tool methods across 6 ToolSets                      │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐       │
│  │  Shell  │ │ Memory  │ │  Web    │ │  Phone  │       │
│  │ (1 tool)│ │ (3 tool)│ │ (2 tool)│ │ (6 tool)│       │
│  └─────────┘ └─────────┘ └─────────┘ └─────────┘       │
│  ┌───────────┐ ┌──────────┐                            │
│  │ Notifs(1) │ │Skills(2) │                            │
│  └───────────┘ └──────────┘                            │
└──────────────────────┬───────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────┐
│                     Data Layer                           │
│                                                          │
│  BorizonDatabase (Room + SQLCipher)                      │
│  ├── MemoryDao      — time-decay relevance ranking      │
│  ├── MessageDao     — conversation message history       │
│  ├── ConversationDao — thread metadata, summaries        │
│  ├── NotificationDao — 24h notification history          │
│  └── ReflectionDao  — thinking session entries           │
│                                                          │
│  Proto DataStore — preferences, model settings           │
└──────────────────────────────────────────────────────────┘
```

---

## Package Layout

```
com.borizon.app/
├── ai/
│   ├── agents/
│   │   └── ReflectAgent.kt         # Conversation handler, agent loop, tool orchestration
│   ├── harness/
│   │   └── ContextCompactor.kt     # Proactive context compression at 60% token budget
│   ├── inference/
│   │   ├── InferenceEngine.kt      # Interface: load, init, generate, stream, multimodal
│   │   ├── LiteRTInferenceEngine.kt # Real LiteRT-LM backend with tool-calling
│   │   ├── ModelManager.kt         # Engine lifecycle, adaptive KV cache, GPU/CPU fallback
│   │   ├── ModelDownloader.kt      # HuggingFace download with progress/resume
│   │   └── ModelDownloadWorker.kt  # WorkManager background download
│   ├── tools/                      # 14 @Tool methods in 6 ToolSets
│   │   ├── ShellTools.kt + ShellSandbox.kt     # Sandboxed command execution
│   │   ├── MemoryTools.kt                      # Save/search/forget user facts
│   │   ├── PhoneTools.kt                       # Calls, SMS, contacts, alarms, calendar, apps
│   │   ├── WebTools.kt                         # Brave Search + web page reader
│   │   ├── NotificationTools.kt                # Read/search notification history
│   │   ├── SkillTools.kt                       # List/run extensible skills
│   │   ├── InstalledAppsTools.kt               # App listing/details (accessed via PhoneTools)
│   │   ├── BorizonActions.kt                   # Action channel events for UI
│   │   └── JavascriptBridge.kt                 # WebView↔Kotlin bridge for skills
│   ├── prompts/
│   │   ├── AgentSystemPrompt.kt    # System prompt with IF-THEN tool triggers
│   │   └── ReflectionTemplates.kt  # Starter templates for conversations
│   └── notifications/
│       └── BorizonNotificationListener.kt  # NotificationListenerService
├── ui/
│   ├── screens/
│   │   ├── BorizonViewModel.kt     # Central ViewModel — owns agents, tools, state
│   │   ├── ChatScreen.kt           # Main chat UI with streaming, tools, multimodal
│   │   ├── ChatUiState.kt          # UI state models
│   │   ├── MemoryScreen.kt         # Memory browser
│   │   ├── SettingsScreen.kt       # Model, API key, preferences
│   │   ├── ModelDownloadScreen.kt  # Model download with progress
│   │   ├── OnboardingScreen.kt     # First-launch onboarding
│   │   ├── BiometricScreen.kt      # Biometric authentication gate
│   │   ├── WelcomeScreen.kt        # Welcome/splash
│   │   └── MainActivity.kt         # Single activity, Compose entry
│   ├── components/                 # Reusable UI components
│   │   ├── ChatMessageBubble.kt    # Message rendering with markdown
│   │   ├── MarkdownRenderer.kt     # CommonMark-compliant markdown
│   │   ├── ToolTimelinePanel.kt    # Tool execution progress
│   │   ├── VoiceAmplitudeVisualizer.kt  # Voice input visualization
│   │   ├── AudioPlaybackPanel.kt   # Audio message playback
│   │   ├── CameraCaptureSheet.kt   # Photo capture
│   │   ├── WebViewCard.kt          # Inline web content
│   │   ├── ConversationDrawer.kt   # Conversation history sidebar
│   │   ├── SkillManagerSheet.kt    # Skill browser
│   │   ├── ModelConfigSheet.kt     # Model parameter tuning
│   │   └── ...                     # Empty states, animations, shapes, cards
│   ├── theme/                      # "Warm Dusk" design system
│   │   ├── Color.kt                # Amber, clay, copper palette
│   │   ├── Theme.kt                # Material 3 dynamic theming
│   │   ├── BorizonTheme.kt         # Custom theme extensions
│   │   ├── Type.kt                 # Typography scale
│   │   ├── Shape.kt                # Rounded corner system
│   │   ├── Font.kt                 # Custom font loading
│   │   ├── Motion.kt               # Animation specifications
│   │   ├── SurfaceContainers.kt    # Surface elevation system
│   │   └── BorizonSemanticColors.kt # Semantic color tokens
│   └── navigation/
│       └── BorizonNavigation.kt    # Bottom nav: Chat, Memory, Settings
├── data/
│   ├── models/                     # Room entities + data classes
│   │   ├── Conversation.kt         # Thread metadata, summary, pin state
│   │   ├── Message.kt              # Chat messages + ChatMessage + MessageType
│   │   ├── MemoryEntry.kt          # User facts with category, importance, access tracking
│   │   ├── NotificationEntry.kt    # Captured notifications
│   │   ├── Reflection.kt           # Thinking session entries
│   │   ├── UserPreferences.kt      # Proto DataStore preferences
│   │   └── Models.kt               # Redirect file (split into individual models)
│   ├── dao/                        # Room DAOs
│   │   ├── MemoryDao.kt            # Time-decay relevance search, access tracking
│   │   ├── MessageDao.kt           # Message CRUD by conversation
│   │   ├── ConversationDao.kt      # Thread management
│   │   ├── NotificationDao.kt      # 24h notification queries
│   │   └── ReflectionDao.kt        # Reflection entries
│   ├── database/
│   │   ├── BorizonDatabase.kt      # Room + SQLCipher + Android Keystore key management
│   │   └── Converters.kt           # Room type converters
│   ├── PreferencesManager.kt       # Proto DataStore wrapper
│   ├── BorizonSettingsSerializer.kt # Settings serialization
│   └── SkillSettingsSerializer.kt  # Skill state serialization
├── audio/
│   ├── SpeechTranscriber.kt        # STT via Android SpeechRecognizer
│   ├── AudioRecorder.kt            # WAV file recorder
│   └── SpeechPlayer.kt             # TTS playback
├── skills/
│   └── SkillManager.kt             # Skill discovery, loading, SKILL.md parsing
├── di/
│   ├── AppModule.kt                # Hilt module — provides singletons
│   └── AppLifecycleProvider.kt     # Application lifecycle awareness
├── util/
│   ├── Extensions.kt               # Kotlin extension functions
│   └── BitmapUtils.kt              # Image processing helpers
├── BorizonApplication.kt           # Application class with Hilt entry point
└── assets/
    └── skills/
        ├── morning-briefing/    # Calendar, notifications, weather, preferences
        │   └── SKILL.md
        ├── phone-status/        # Battery, storage, memory check
        │   └── SKILL.md
        ├── study-buddy/         # Spaced repetition quizzing and review
        │   └── SKILL.md
        ├── wellness-check/      # Daily mood, health, habit tracking
        │   └── SKILL.md
        └── travel-helper/       # Offline packing lists and itineraries
            └── SKILL.md
```

---

## AI Pipeline

### ReflectAgent — Conversation Handler

The central orchestrator for every conversation turn. Implements a **Pi-style ReAct loop** adapted for E4B's constraints.

**Flow:**
1. User sends message → `reflect()` called
2. Message persisted to Room (Conversation + Message entities)
3. Top-8 relevant memories injected into system prompt via `MemoryDao.getRelevant()`
4. Agent system prompt built with `AgentSystemPrompt.build()`
5. `getMatchedSkill()` checks user message against skill trigger phrases
   - If matched: skill instructions injected directly into the prompt (no `loadSkill` round-trip)
6. **ReAct Loop** (max 3 iterations):
   - `ToolCallTracker.reset()` — clear tool counter
   - `runAgentTurn()` → sends prompt to `ModelManager.generateStream()`
   - LiteRT processes with `automaticToolCalling = true` (inner tool loop)
   - Each `@Tool` method increments `ToolCallTracker`
   - After generation, check result:
     - **Tools called → STOP** (model acted, trust response)
     - **Silent tools → followup turn** (model used tools but no text)
     - **Substantive text → STOP** (real answer, no tools needed)
     - **Acknowledgment → CONTINUE** ("I'll check" without acting → force turn)
     - **Skill ignored → CONTINUE** (skill injected but model didn't call tools → force turn)
   - Force turn prompt: "Do NOT acknowledge. Call the right tool NOW."
7. Final response streamed to UI via `StateFlow<String>`
8. Context compactor checks budget after each turn

**Why inverted from Pi?** Pi's loop continues while tools ARE called (`hasMoreToolCalls = toolCalls.length > 0`). Borizon loops when tools are NOT called — the E4B failure mode is acknowledging without acting. This is the inverse of Pi's design because LiteRT handles the inner tool loop internally.

**Key properties:**
- Persistent conversation — KV cache reuse across turns
- Atomic generation guard — prevents concurrent inference calls
- Session tracking — date + session ID for file organization
- `ToolCallTracker` — shared `AtomicInteger` for agent loop decisions

### ContextCompactor — Proactive Compression

Prevents context degradation before it happens.

**Algorithm:**
1. After each turn, estimate token count: `(content.length + thinking.length) / 4 + 50`
2. Add overhead for tool calls: `200 + 100 × eventCount` per message with tool events
3. If estimated tokens ≥ 60% of `maxSafeTokens` → trigger compaction
4. Build transcript from most recent messages (max 8000 chars, newest-first)
5. Run `ModelManager.generateAnalysis()` with compaction prompt
6. Replace old messages with single summary message
7. Reset conversation with compacted history

**Why 60%?** On-device models degrade rapidly when approaching KV cache limits. 60% gives headroom for multi-tool turns that suddenly add many tokens.

### AgentSystemPrompt — Prompt Design

The system prompt follows a structured, token-efficient design:

```
AGENT CORE (behavioral rules, ~120 tokens)
  - NEVER acknowledge without acting
  - Call tools in sequence until real answer
  - After loading a skill, execute immediately
RESPONSE STYLE (~30 tokens)
TOOL DISPATCH (IF-THEN trigger map, ~80 tokens)
SHELL RULES (safe mode constraints, ~40 tokens)
  - No pipes in safe mode
  - Put filter commands first
  - Only use shell mode for true pipes
FORMAT + MEMORY CATEGORIES (~30 tokens)
Current time: ___TIME___
```

**Design philosophy:**
- **Behavioral rules first** — "NEVER acknowledge" is the #1 rule because E4B tends to say "I'll check" without acting
- **IF-THEN triggers** — explicit "personal fact → memorySave" mappings help the small model choose correctly
- **Under 15 words per tool description** — reduces schema token overhead
- **No examples in prompt** — saves tokens; tool descriptions are self-documenting
- **Time injection** — `___TIME___` replaced at build time for temporal awareness
- **Shell rules** — explicit "no pipes in safe mode" prevents the model from generating `cat file | head` which fails in safe mode ProcessBuilder

---

## Inference Layer

### ModelManager

Manages the LiteRT-LM engine lifecycle.

**Responsibilities:**
- Load/unload model files (`gemma-4-E2B-it.litertlm` or `gemma-4-E4B-it.litertlm`)
- Compute adaptive `maxTokens` based on device RAM:

| Device RAM | E2B maxTokens | E4B maxTokens |
|------------|--------------|--------------|
| 8 GB | 4096 | N/A (too small) |
| 12 GB | 8192 | 4096 |
| 16 GB | 16384 | 8192 |

- Handle GPU→CPU fallback chain:
  1. Attempt NPU/GPU acceleration via LiteRT GPU delegate
  2. If GPU fails (no OpenCL, driver crash, multi-signature model) → fall back to CPU
  3. E4B multi-signature workaround: `visionBackend = null` (vision on CPU, text on GPU)
- Persistent conversation management (create, reset, reuse KV cache)
- One-shot analysis (temp conversation for compaction/reflection)

### LiteRTInferenceEngine

The real inference backend. Implements `InferenceEngine`.

**Key behaviors:**
- `automaticToolCalling = true` — LiteRT handles tool invocation natively
- Streaming output with `tokensPerSecond` tracking
- Multimodal input: text + images + audio
- Thinking token extraction from `message.channels["thought"]`
- Stop response preserves partial text in conversation

### ModelDownloader + Worker

Background model download from HuggingFace.

- E2B: `gemma-4-E2B-it.litertlm` (~2.6 GB)
- E4B: `gemma-4-E4B-it.litertlm` (~3.7 GB)
- Progress reporting with speed and ETA
- Resume support via `.tmp` file management
- Size verification (90% threshold) after download
- WorkManager integration for background + reboot persistence

---

## Tool System

### Registration Flow

Tools are registered in `BorizonViewModel` at conversation initialization:

```kotlin
// Core tools — always registered (E2B and E4B)
coreTools = [shellTools, memoryTools, webTools, phoneTools, notificationTools]

// Extended tools — E4B only (reduces schema tokens for E2B)
extendedTools = [skillTools]

// E2B gets coreTools only (13 @Tool methods)
// E4B gets coreTools + extendedTools (14 @Tool methods)
```

**Why tiered?** E2B has a smaller KV cache and token budget. Fewer tool descriptions in the schema means more room for conversation. Each tool adds ~38 tokens to the context schema (~530 tokens total, down from ~960 with the old 25-tool set).

### ToolSet Summary

| ToolSet | @Tool Methods | Tier | Purpose |
|---------|--------------|------|---------|
| ShellTools | 1 | Core | Execute sandboxed shell commands |
| MemoryTools | 3 | Core | Save, search, delete user memories |
| WebTools | 2 | Core | Web search + page reader |
| PhoneTools | 6 | Core | Calls, SMS, contacts, alarms, calendar, apps |
| NotificationTools | 1 | Core | Read and search notification history |
| SkillTools | 2 | Extended | List and run extensible skills (5 built-in) |

**E2B: 5 core ToolSets = 13 @Tool methods**
**E4B: 5 core + 1 extended = 14 @Tool methods** (SkillTools adds listSkills + loadSkill)

### BorizonActions — UI Feedback Channel

Tools communicate with the UI through a `Channel<BorizonAction>`:

| Action | Purpose |
|--------|---------|
| `Progress` | Show tool execution state in chat (label, in-progress, tool type) |
| `Dashboard` | Show inline WebView for web content |
| `AskUser` | Show dialog for user input (e.g., confirm contact creation) |
| `Confirm` | Show confirmation dialog before destructive operations |

This pattern decouples tool execution from UI rendering — tools run on IO dispatchers while the UI observes the channel via `LaunchedEffect`.

### ShellTools Security Model

Two execution modes via `ShellSandbox`:

**Safe mode (default):**
- Direct `ProcessBuilder` execution — no shell interpretation
- Binary allowlist: 60+ allowlisted binaries at `/system/bin/`
- No pipes, redirects, backticks, `$()` expansion

**Shell mode:**
- Runs via `sh -c` for pipelines and complex commands
- Pattern denylist: 60+ dangerous patterns blocked (rm -rf, su, sudo, dd, mkfs, chmod, etc.)
- Regex-based detection

**Both modes enforce:**
- 15-second timeout with process kill
- 80-line / 8000-char output cap
- Environment scrubbing (4 vars only: PATH, HOME, USER, TERM)
- No stdin
- Single-execution mutex (prevents concurrent commands)
- Dedicated working directory

---

## Data Layer

### Entity Relationships

```
Conversation (1) ──── (N) Message
       │
       ├── (N) Reflection
       │
       └── (0..1) MemoryEntry.sourceConversationId

MemoryEntry (independent, cross-conversation)

NotificationEntry (independent, auto-expire 24h)
```

### Database Schema

**conversations**
| Column | Type | Notes |
|--------|------|-------|
| id | Long (PK) | Auto-generated |
| title | String | First message or auto-generated |
| createdAt | Long | Unix timestamp |
| updatedAt | Long | Updated on each message |
| messageCount | Int | Denormalized for performance |
| isPinned | Boolean | Pin state |
| sessionRef | String | "2026-04-15/session-0915" |
| summary | String | Compaction summary |
| sessionSummary | String | Comprehensive session summary |

**messages**
| Column | Type | Notes |
|--------|------|-------|
| id | Long (PK) | Auto-generated |
| conversationId | Long (FK → conversations) | CASCADE on delete |
| role | MessageRole | USER, ASSISTANT, SYSTEM |
| content | String | Message text |
| timestamp | Long | Unix timestamp |
| tokenCount | Int | Estimated token count |

**memories**
| Column | Type | Notes |
|--------|------|-------|
| id | Long (PK) | Auto-generated |
| content | String | The fact/preference/event |
| category | MemoryCategory | PREFERENCE, FACT, RELATIONSHIP, EVENT, SKILL |
| importance | Float | 0.0–1.0, affects ranking |
| accessCount | Int | Times used in responses |
| lastAccessed | Long | Unix timestamp |
| createdAt | Long | Unix timestamp |
| sourceConversationId | Long? | Origin conversation |

**notifications**
| Column | Type | Notes |
|--------|------|-------|
| id | Long (PK) | Auto-generated |
| packageName | String | App package |
| title | String | Notification title |
| text | String | Notification body |
| timestamp | Long | Unix timestamp |

### Memory Ranking Algorithm

MemoryDao uses a **time-decay relevance** formula for `getRelevant()`:

```sql
ORDER BY importance * (1.0 / (1.0 + (:now - lastAccessed) / 86400000.0)) DESC
```

- Recent memories get a boost (accessed today → full weight)
- Importance score acts as a multiplier
- Access tracking: `incrementAccessCounts()` called when memories are used in responses
- Pruning: `pruneToMax()` keeps top 500 memories by importance

### Encryption Architecture

```
Android Keystore (hardware-backed)
  └── AES-256 key (non-extractable)
       └── Encrypts SQLCipher passphrase (512-bit)
            └── Stored in SharedPreferences as Base64(ciphertext) + Base64(IV)

SQLCipher
  └── Opens borizon.db with encrypted passphrase
       └── All data encrypted at rest
```

Key properties:
- SQLCipher passphrase is 512-bit random, never stored in plaintext
- Keystore key is hardware-backed (cannot be extracted, even with root)
- Legacy migration: plaintext keys auto-migrated to Keystore encryption
- Key invalidation (biometric change, security patch) → graceful data loss handling

---

## UI Layer

### State Flow

```
BorizonViewModel (owns all state)
  │
  ├── sessionMessages: StateFlow<List<ChatMessage>>
  ├── streamingText: StateFlow<String>
  ├── isGenerating: StateFlow<Boolean>
  ├── isConversationReady: StateFlow<Boolean>
  │
  └── reflectsAgent.actionChannel → ChatScreen LaunchedEffect
       └── Collects BorizonAction → updates UI
```

### Screen Architecture

| Screen | Purpose |
|--------|---------|
| `MainActivity` | Single activity, Compose entry, biometric check |
| `ChatScreen` | Main chat UI — messages, input, voice, camera, streaming |
| `MemoryScreen` | Browse/search/delete stored memories |
| `SettingsScreen` | Model selection, API keys, preferences |
| `ModelDownloadScreen` | Download progress with speed/ETA |
| `OnboardingScreen` | First-launch setup (model, name, preferences) |
| `BiometricScreen` | Biometric authentication gate |
| `WelcomeScreen` | Splash/brand screen |

### Theme: Warm Dusk

Dark Material 3 theme with warm tones:
- **Primary:** Amber/gold accent
- **Surface:** Dark clay/brown containers
- **Secondary:** Copper highlights
- Custom semantic colors for status, success, error states
- Custom shape system (rounded message bubbles)
- Motion specifications for animations

---

## Audio System

| Component | Technology | Purpose |
|-----------|-----------|---------|
| `SpeechTranscriber` | Android `SpeechRecognizer` | Voice-to-text for voice messages |
| `AudioRecorder` | Custom WAV recorder (PCM 16-bit) | Record audio clips |
| `SpeechPlayer` | Android `TextToSpeech` | Read AI responses aloud |

Audio clips are WAV format (header + PCM), session-only (not persisted to Room).

---

## Skills System

### SkillManager

Discovers and loads skills from `assets/skills/` and imported directories.

**Built-in skills:**
- `morning-briefing` — Calendar, notifications, weather, preferences summary. Triggers: "morning briefing", "daily summary", "good morning".
- `phone-status` — Battery, storage, memory health check. Triggers: "how is my phone", "phone status", "device status", "phone health", "status check".
- `study-buddy` — Adaptive study session with spaced repetition and quiz tracking. Triggers: "study session", "help me study", "quiz me", "review notes".
- `wellness-check` — Daily wellness check-in tracking mood, habits, and health patterns. Triggers: "wellness check", "how am I doing", "mood check", "daily check-in".
- `travel-helper` — Offline travel assistant with packing lists and itinerary planning. Triggers: "travel", "trip", "vacation", "packing", "itinerary".

**Skill structure:**
```
skills/
└── morning-briefing/
    ├── SKILL.md          # Manifest (name, description, triggers)
    └── scripts/          # Optional JS scripts
        └── main.js
```

**SKILL.md format:**
```markdown
---
name: morning-briefing
description: Morning briefing with calendar, notifications, weather. Triggers: "morning briefing", "daily summary", "good morning".
---
# Instructions for the AI...
```

### Skill Auto-Inject

When a user's message matches a skill's trigger phrases, ReflectAgent injects the skill instructions directly into the prompt — no `loadSkill` round-trip needed. This was added because E4B often acknowledges ("I'll prepare your briefing") without actually calling `loadSkill`.

**Flow:**
1. `getMatchedSkill(userText)` — checks user message against quoted trigger phrases in skill descriptions
2. If matched: instructions appended to user message with `[INSTRUCTIONS — execute these steps now]`
3. If model still ignores the instructions (no tools called): agent loop force turn kicks in

**Trigger extraction:** Parses quoted phrases (`"good morning"`) from the skill description. Skills must include trigger phrases in their description frontmatter.

### JavascriptBridge

WebView-based skill execution:

1. SkillTools calls `SkillManager.loadSkill(name)`
2. `JavascriptBridge` creates a WebView with `WebViewAssetLoader`
3. `borizon_skill_execute(data)` is called as the entry point
4. Skill runs in sandboxed WebView (no external network)
5. `BorizonBridge.onResultReady(result)` returns results to Kotlin
6. 60-second execution timeout
7. WebView destroyed after completion

---

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| DI framework | Hilt + KSP (not KAPT) | Faster compilation, KAPT deprecated |
| Database | Room + SQLCipher | Type-safe queries + encryption out of the box |
| Tool calling | LiteRT native `automaticToolCalling` | No custom parsing needed, native support |
| Tool registration | Tiered (core/extended) | Reduces schema tokens for smaller models |
| Context management | Proactive at 60% | Prevents degradation, not reactive at 100% |
| Shell security | Dual-mode (safe/shell) | Balance between capability and safety |
| Preferences | Proto DataStore | Type-safe, observable, better than SharedPreferences |
| Markdown | halilibo richtext (CommonMark) | Compose-native, well-maintained |
| Navigation | Bottom nav (3 screens) | Simple, phone-friendly |
