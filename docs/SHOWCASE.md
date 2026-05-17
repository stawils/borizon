# Borizon — Technical Showcase

**A comprehensive look at what we built.**

---

## By the Numbers

| Metric | Count |
|--------|-------|
| Kotlin source files | **99** |
| Lines of code | **18,000** |
| AI tools (native @Tool methods) | **14** |
| ToolSets (LiteRT registration) | **6** |
| Tool parameters (@ToolParam) | **~45** |
| Shell allowlisted binaries | **50+** |
| Shell blocked dangerous patterns | **83** |
| Room database entities | **5** |
| Room DAOs with structured queries | **5** |
| Database schema iterations | **14** |
| Android permissions declared | **14** |
| Jetpack Compose UI screens | **10** |
| Reusable UI components | **20** |
| Theme system files | **9** |
| Message rendering types | **9** |
| Inference engine interface methods | **12** |
| Memory categories | **5** |
| Input modes (text, voice, image, audio) | **4** |

---

## 1. Real On-Device AI Agent

Not a chatbot wrapper. Gemma 4 runs entirely on the phone.

```
User speaks → STT transcribes → ReflectAgent picks tools →
  model calls shellExecute("df -h /sdcard") →
  model calls memorySave("has 32GB storage") →
  model responds with analysis → TTS reads it back
```

**What makes this real agency:**
- **15 native tools** registered via LiteRT `automaticToolCalling` — the model decides what to call, when, and with what parameters
- **Multi-tool chaining** — the model calls multiple tools in one turn (e.g., search contacts then make a call)
- **Agent loop** — OBSERVE → THINK → ACT → RESPOND, with a forced followup turn if the model calls tools but goes silent
- **Autonomous shell access** — when no dedicated tool covers the need, the model drops into a sandboxed shell and runs real commands

### Inference Engine

12-method interface supporting the full multimodal pipeline:

| Method | Purpose |
|--------|---------|
| `initConversation` | Create persistent session with tools |
| `generateStream` | Stream tokens with TPS tracking |
| `generateStreamMultimodal` | Text + images together |
| `generateStreamWithAudio` | Audio input with thinking tokens |
| `analyzeImage` | One-shot vision analysis |
| `transcribe` | Audio file → text |
| `analyze` | One-shot with custom system prompt |
| `stopResponse` | Cancel mid-generation, preserve partial |
| `resetConversation` | Clear KV cache for new chat |

**Hardware acceleration chain:** NPU → GPU → CPU, with automatic fallback. E4B multi-signature workaround: `visionBackend = null` (vision on CPU, text on GPU).

**Adaptive KV cache:**

| Device RAM | E2B Tokens | E4B Tokens |
|------------|-----------|-----------|
| 8 GB | 4,096 | — |
| 12 GB | 8,192 | 4,096 |
| 16 GB | 16,384 | 8,192 |

---

## 2. 14 Phone-Native Tools

The model doesn't just talk — it acts. Every tool is a real `@Tool`-annotated Kotlin method that executes on the device.

### Core Tools (13 — always registered)

**ShellTools** — Sandboxed command execution
- `shellExecute` — Run commands in a dual-mode sandbox (safe binary allowlist OR shell with 83 dangerous patterns blocked)
- 50+ allowlisted binaries: `ls`, `cat`, `grep`, `find`, `df`, `du`, `pm`, `getprop`, `ps`, `top`, `uname`, `dumpsys`...
- 83 blocked dangerous patterns: `rm -rf`, `su`, `sudo`, `dd`, `mkfs`, `chmod`, `curl`, `ssh`, `base64`...
- 15s timeout, 80-line cap, environment scrubbed, no stdin, single-execution mutex

**MemoryTools** — Persistent personal memory
- `memorySave` — Store facts with category + importance score
- `memorySearch` — Time-decay relevance search across all memories
- `memoryForget` — Delete by ID
- 5 categories: PREFERENCE, FACT, RELATIONSHIP, EVENT, SKILL
- Top-8 most relevant injected into every conversation turn
- Auto-prunes to 500 entries by importance ranking

**PhoneTools** — 6 tools covering the full phone
- `setTimeAction` — Alarms and timers with flexible scheduling
- `communicate` — Phone calls, SMS, emails
- `manageContacts` — Search and create contacts
- `appControl` — Launch apps, settings, share text, open URLs
- `calendarAction` — Read and create calendar events
- `readSms` — Read SMS history + call history (`action=calls`)

**WebTools** — Web search + content reader
- `webSearch` — Brave Search API
- `readWebPage` — Extract text content from URLs

**NotificationTools** — Real-time notification intelligence
- `readRecentNotifications` — Latest notifications + keyword search (`query=`)
- Auto-hides sensitive content (banking, 2FA)
- 24-hour auto-expiry

### Extended Tools (2 — E4B only)

| Tool | What it does |
|------|-------------|
| `listSkills` | List available skills |
| `loadSkill` | Execute a skill by name |

### Why Tiered?

E2B has a smaller KV cache. Every `@Tool` adds ~38 tokens to the context schema. 5 ToolSets instead of 6 saves ~76 tokens — and the full 14-tool set costs ~530 tokens total (down from ~960 with 25 tools), freeing ~430 tokens for conversation.

---

## 3. Proactive Context Management

Context doesn't degrade — it's managed before it becomes a problem.

**ContextCompactor** runs after every turn:
1. Estimates tokens: `(content.length + thinking.length) / 4 + 50` per message, plus `200 + 100 × toolEvents` overhead
2. If estimated tokens ≥ **60% of max** → trigger compaction
3. Builds transcript from most recent messages (max 8000 chars, newest-first)
4. Runs model-powered summarization via `ModelManager.generateAnalysis()`
5. Replaces old messages with compacted summary
6. Resets conversation with preserved history

**Why 60%?** On-device models degrade rapidly near KV cache limits. The proactive threshold gives headroom for multi-tool turns that suddenly add many tokens.

---

## 4. Encrypted Personal Memory

Not just stored — encrypted, ranked, and auto-managed.

```
Android Keystore (hardware TEE/StrongBox)
  └── AES-256-GCM key (non-extractable, even with root)
       └── Encrypts 512-bit SQLCipher passphrase
            └── Stored as ciphertext + IV in SharedPreferences
                 └── Opens borizon.db with full-disk encryption
```

**Memory ranking algorithm:**
```sql
ORDER BY importance * (1.0 / (1.0 + (now - lastAccessed) / 86400000.0)) DESC
```
- Recent access boosts relevance
- Importance (0.0–1.0) acts as multiplier
- Access count incremented when memory used in a response
- Auto-prunes to top 500 by importance

**5 Room entities** with full CRUD:
| Entity | Relationships | Key Feature |
|--------|--------------|-------------|
| Conversation | has many Messages | Session tracking, compaction summaries |
| Message | belongs to Conversation | Cascade delete, 9 render types |
| MemoryEntry | independent, cross-conversation | Time-decay ranking, category indexing |
| NotificationEntry | independent, auto-expire | Sensitive content hiding |
| Reflection | optional link to Conversation | Thinking session entries |

14 database schema versions — real iterative development, not a weekend prototype.

---

## 5. Defense-in-Depth Shell Security

The most carefully designed part of the system. The AI gets real shell access — and we made sure it can't destroy anything.

**Layer 1: Binary Allowlist (Safe Mode)**
- Direct `ProcessBuilder` execution — no shell interpretation
- 50+ allowlisted binaries at `/system/bin/{name}`
- No pipes, redirects, backticks, `$()`, or any shell expansion
- Blocked: `/data/data/`, `/data/app/`, app private directories

**Layer 2: Pattern Denylist (Shell Mode)**
- `sh -c` for pipelines and complex commands
- 83 regex patterns blocking:
  - Privilege escalation: `su`, `sudo`
  - Destructive: `rm -rf`, `dd`, `mkfs`, `format`
  - Permission changes: `chmod`, `chown`
  - Remote access: `curl`, `wget`, `ssh`, `nc`, `telnet`
  - Code execution: `python`, `perl`, `ruby`, `bash`
  - Bypass vectors: `base64`, `xxd -r` (decode to bypass filters)

**Layer 3: Universal Constraints (both modes)**
| Constraint | Value |
|-----------|-------|
| Timeout | 15 seconds, then SIGKILL |
| Output cap | 80 lines / 8,000 characters |
| Environment | 4 variables only (PATH, HOME, USER, TERM) |
| Stdin | None — no interactive input |
| Concurrency | Single-execution mutex |
| Working directory | Dedicated, not root |

---

## 6. Full Jetpack Compose UI

Not a prototype UI — a polished, themed, production-quality interface.

**10 screens:**
Chat · Memory Browser · Settings · Model Download · Onboarding · Biometric Lock · Welcome · Main Activity · Ingest Activity · TOS

**20 reusable components:**
- Markdown rendering (CommonMark-compliant)
- Streaming text with TPS counter
- Voice amplitude visualizer
- Audio playback panel
- Camera capture sheet
- Tool execution timeline
- Inline WebView cards
- Conversation drawer
- Skill manager sheet
- Model config sheet
- Animated splash screen
- Custom message bubble shapes
- Typing indicator
- Empty state illustrations
- Custom switches
- Loading states
- And more...

**"Warm Dusk" design system (9 files):**
Custom color palette (amber/clay/copper) · Typography scale · Shape system · Motion specs · Surface elevation · Semantic color tokens · Custom font loading · Material 3 theming

**9 message render types:**
Text · Thinking (chain-of-thought) · System · Progress (tool execution) · Image · Audio · Config Change · WebView · Custom

---

## 7. Multimodal Input

Users can interact through 4 input modes:

| Mode | Path | Technology |
|------|------|-----------|
| **Text** | Type in chat | Markdown composer |
| **Voice** | Tap mic → STT | Android SpeechRecognizer → WAV → model ASR |
| **Camera** | Tap camera → capture | CameraX → Bitmap → multimodal inference |
| **Audio clip** | Record audio | WAV recorder → `generateStreamWithAudio` |

All modes feed into the same ReflectAgent pipeline with full tool access.

---

## 8. Extensible Skills System

Prompt-based skills loaded from `SKILL.md` files:

- **Skill manifest:** `SKILL.md` with name, description, trigger phrases
- **Auto-inject:** When user message matches a trigger phrase, instructions are injected directly into the prompt
- **Execution:** Skills orchestrate existing tools (memorySave, shellExecute, readRecentNotifications, etc.) — no new code needed
- **Sandbox:** JS skills run in a WebView, local assets only, 60s timeout

**5 built-in skills:**

| Skill | Purpose | Triggers |
|-------|---------|----------|
| `morning-briefing` | Calendar, notifications, weather, preferences | "morning briefing", "daily summary", "good morning" |
| `phone-status` | Battery, storage, memory health check | "how is my phone", "phone status", "phone health" |
| `study-buddy` | Spaced repetition quizzing and review | "study session", "quiz me", "review notes" |
| `wellness-check` | Daily mood, health, and habit tracking | "wellness check", "how am I doing", "mood check" |
| `travel-helper` | Offline packing lists and itineraries | "travel", "trip", "packing", "itinerary" |

Skills are discovered from `assets/skills/` and can be imported from device storage via SAF (Storage Access Framework).

---

## 9. Privacy by Architecture

Not a privacy policy — a privacy architecture.

| Guarantee | Implementation |
|-----------|---------------|
| No cloud processing | LiteRT-LM runs 100% on-device |
| Encrypted storage | SQLCipher + Android Keystore (hardware TEE) |
| Biometric lock | Mandatory on every launch |
| No accounts | No sign-up, no email, no server |
| No telemetry | Zero analytics, crash reporting, or tracking |
| Opt-in web | Manual API key required for web search |
| Notification privacy | Sensitive app content auto-hidden |
| Shell sandbox | 83 dangerous patterns blocked, 50+ binary allowlist |

**The `INTERNET` permission exists solely for:**
1. First-launch model download from HuggingFace (HTTPS)
2. Opt-in Brave Search queries (user's own API key)

Once the model is downloaded, the app works fully offline. On a plane. On the subway. Anywhere.

---

## 10. Technical Depth

**What judges should look at:**

| File | Why it matters |
|------|---------------|
| `ShellSandbox.kt` | 3-layer security model for AI-driven shell access |
| `ReflectAgent.kt` | Full agent loop with tool orchestration, memory injection, silent-call detection |
| `ContextCompactor.kt` | Proactive token budget management at 60% threshold |
| `BorizonDatabase.kt` | SQLCipher + Keystore encryption with key rotation and invalidation handling |
| `MemoryDao.kt` | Time-decay relevance ranking with access tracking |
| `LiteRTInferenceEngine.kt` | Real LiteRT-LM integration with GPU/CPU fallback |
| `ModelManager.kt` | Adaptive KV cache scaling, multi-signature GPU workaround |
| `AgentSystemPrompt.kt` | Token-efficient prompt with IF-THEN tool triggers for small models |
| `PhoneTools.kt` | 6 compound tools covering calls, SMS, contacts, alarms, calendar, apps |
| `BorizonViewModel.kt` | Central orchestrator managing 6 ToolSets, model lifecycle, UI state |

---

## Tech Stack Summary

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Kotlin | 2.0+ |
| UI | Jetpack Compose + Material 3 | BOM-managed |
| AI | Gemma 4 E2B/E4B via LiteRT-LM | 4-bit quantized |
| Tool-Calling | LiteRT `automaticToolCalling` | Native |
| Database | Room + SQLCipher | v14 schema |
| Encryption | Android Keystore (AES-256-GCM) | Hardware-backed |
| DI | Hilt + KSP | (not KAPT) |
| Preferences | Proto DataStore | Protobuf lite |
| Navigation | Navigation Compose | Bottom nav |
| Markdown | halilibo richtext | CommonMark |
| Web Search | Brave Search API via OkHttp | HTTPS |
| Camera | CameraX | View + Lifecycle |
| Background | WorkManager | Model downloads |
| Min SDK | API 28 (Android 9) | Wide device coverage |
| Target SDK | API 35 (Android 15) | Latest |
