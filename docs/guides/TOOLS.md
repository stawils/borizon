# Borizon — Tool Reference

**Complete reference for all 15 on-device tools across 6 ToolSets (E4B). E2B gets 13 tools across 5 ToolSets.**

---

## Overview

Borizon's tools are registered as LiteRT `ToolSet` instances with `@Tool`-annotated methods. The model invokes them autonomously via `automaticToolCalling = true` — the user never calls tools directly.

**Tier system:**
- **Core** (always registered) — 13 tools across 5 ToolSets
- **Extended** (E4B only) — 2 tools in 1 ToolSet (SkillTools)

E2B gets core only to stay within its smaller KV cache budget. The full 15-tool schema costs ~530 tokens — down from ~960 with the previous 25-tool set, freeing ~430 tokens for conversation context.

---

## Core Tools

### ShellTools

**Purpose:** Execute sandboxed commands on the device when no dedicated tool covers the need.

| Method | Description |
|--------|-------------|
| `shellExecute` | Run a shell command. Uses safe mode (allowlisted binaries) by default, shell mode (`sh -c`) for pipelines. |

**Security model:**
- **Safe mode:** Direct `ProcessBuilder` execution. Only 60+ allowlisted binaries (`ls`, `cat`, `grep`, `find`, `pm`, `getprop`, `df`, `du`, etc.). No shell interpretation.
- **Shell mode:** `sh -c` with 60+ dangerous pattern blocks (`rm -rf`, `su`, `sudo`, `dd`, `mkfs`, `chmod`, `curl`, `ssh`, etc.)
- **Both modes:** 15s timeout, 80-line output cap, environment scrubbed (PATH, HOME, USER, TERM only), no stdin, single-execution mutex.

**Permissions:** None (runs in app sandbox, no dangerous permissions needed)

---

### MemoryTools

**Purpose:** Persistent structured memory across conversations. Automatically saves facts, preferences, and context.

| Method | Parameters | Description |
|--------|-----------|-------------|
| `memorySave` | `content`, `category`, `importance` | Save a fact about the user |
| `memorySearch` | `query` | Search stored memories |
| `memoryForget` | `id` | Delete a memory by ID |

**Memory categories:**

| Category | Examples |
|----------|---------|
| `PREFERENCE` | "prefers dark mode", "vegetarian", "likes early mornings" |
| `FACT` | "works as a teacher", "lives in Berlin" |
| `RELATIONSHIP` | "sister Ana is a doctor", "colleague David handles ops" |
| `EVENT` | "dentist Friday 3pm", "flight to Tokyo March 12" |
| `SKILL` | "learning Spanish", "practicing meditation daily" |

**Ranking:** Time-decay formula: `importance / (1 + days_since_access)`. Top-8 injected into system prompt each turn. Prunes to 500 entries max.

**Permissions:** None (internal database)

---

### WebTools

**Purpose:** Web search and content reading. Requires user-configured Brave Search API key.

| Method | Parameters | Description |
|--------|-----------|-------------|
| `webSearch` | `query` | Search the web using Brave Search API |
| `readWebPage` | `url` | Read a web page's text content |

**Permissions:** `INTERNET`, `ACCESS_NETWORK_STATE`

**Notes:** Web search is opt-in — user must configure their own Brave Search API key in Settings. No data sent to any server except Brave Search (over HTTPS).

---

### PhoneTools

**Purpose:** Interact with the phone's native capabilities via Android intents and content providers. The largest ToolSet with 6 tools.

| Method | Description |
|--------|-------------|
| `setTimeAction` | Set alarms or timers (hour, minute, seconds, label, datetime) |
| `communicate` | Make phone calls, send SMS, compose emails |
| `manageContacts` | Search existing contacts or create new ones |
| `appControl` | Open apps, settings, share text, open URLs |
| `calendarAction` | Read upcoming events or open calendar to create new events (opens editor, no silent writes) |
| `readSms` | Read SMS messages (search by keyword, read thread) or call history (`action=calls`) |

**Permissions:** `READ_CONTACTS`, `WRITE_CONTACTS`, `READ_CALENDAR`, `READ_SMS`, `READ_CALL_LOG`, `CALL_PHONE` (via intent), `SEND_SMS` (via intent), `SET_ALARM`, `SCHEDULE_EXACT_ALARM`

**Note:** `calendarAction` create uses `ACTION_INSERT` intent — opens the system calendar editor prefilled with the event title. No `WRITE_CALENDAR` permission needed because the calendar app handles the write, not Borizon.

**Design:** The `readSms` tool handles both SMS and call history. Use `action=calls` to read call log instead of SMS. This merge reduces the tool schema by eliminating a separate `readCallLog` tool.

---

### NotificationTools

**Purpose:** Access the device's notification history. Powered by `NotificationListenerService`.

| Method | Parameters | Description |
|--------|-----------|-------------|
| `readRecentNotifications` | `limit`, `query` (optional) | Get recent notifications, optionally filtered by keyword search |

**Privacy:** Three-tier content filter:
1. **Blocked** — SMS/dialer notifications dropped entirely (captured by dedicated tools)
2. **Hidden** — Known sensitive apps (banking, health, messaging): title kept, text replaced with `[content hidden — sensitive app]`
3. **Redacted** — Unknown apps with sensitive *content* (OTP keywords, 2FA phrases, transaction terms): matched patterns replaced with `[REDACTED]`

The package-name list covers ~30 apps. Content-based patterns catch OTP/2FA/transaction keywords in any language (English + Arabic). Gaps: regional banks not in the package list rely on the keyword filter. Notifications auto-expire after 24 hours.

**Design:** The `query` parameter is optional. Without it, returns the most recent notifications. With it, searches by keyword — replacing the former separate `searchNotifications` tool.

**Permissions:** `BIND_NOTIFICATION_LISTENER_SERVICE` (requires user to grant in system settings)

---

## Extended Tools (E4B Only)

### SkillTools

**Purpose:** List and run extensible skills loaded from local storage. 5 built-in skills (morning-briefing, phone-status, study-buddy, wellness-check, travel-helper) with prompt-based orchestration of existing tools.

| Method | Description |
|--------|-------------|
| `listSkills` | List available skills with descriptions |
| `loadSkill` | Run a skill by name, passing data |

**Skill execution:** Skills can be prompt-based (SKILL.md instructions injected into the conversation) or JS-based (sandboxed WebView via `JavascriptBridge`). JS skills call `borizon_skill_execute(data)` and return results via `BorizonBridge.onResultReady()`. 60-second timeout, local assets only, no external network.

**Permissions:** None (WebView with local asset loader)

---

### InstalledAppsTools (Unregistered)

**Purpose:** List installed apps or get details about a specific app. Exists as a separate ToolSet but is accessed via `PhoneTools` static cache, not directly registered.

| Method | Description |
|--------|-------------|
| `appInfo` | List apps by category or get details by name |

**Note:** Not counted in the tool registration total — accessed via `PhoneTools` using the static `getCachedApps()` method.

---

## Tool Registration Summary

```
E2B (5 ToolSets, 13 @Tool methods):
├── ShellTools        → 1 tool
├── MemoryTools       → 3 tools
├── WebTools          → 2 tools
├── PhoneTools        → 6 tools
└── NotificationTools → 1 tool

E4B (6 ToolSets, 15 @Tool methods):
├── [All E2B tools above]
└── SkillTools        → 2 tools
```

---

## Removed Tools

The following tools were removed to reduce token schema overhead and simplify the tool surface:

| Removed Tool | Reason | Replacement |
|-------------|--------|-------------|
| `countPhotos` / `recentPhotos` / `searchPhotos` (GalleryTools) | Low value, high token cost | Shell commands can list photos |
| `getBatteryStatus` (BatteryTools) | Shell can check battery | `shellExecute("dumpsys battery")` |
| `readClipboard` (ClipboardTools) | Rare use, token cost | Not replaced |
| `getUsageStats` (UsageStatsTools) | Rare use, token cost | Not replaced |
| `dndControl` (DndTools) | Rare use, token cost | Not replaced |
| `searchNotifications` (NotificationTools) | Merged into existing tool | `readRecentNotifications(query=...)` |
| `readCallLog` (PhoneTools) | Merged into existing tool | `readSms(action=calls)` |
| `deviceControl` (PhoneTools) | Rare use, privacy-sensitive | Not replaced |

**Token savings:** 25 tools (~960 tokens) → 15 tools (~530 tokens) = ~430 tokens freed (~45% reduction)

---

## How Tool Calling Works

### Architecture

The tool calling system has two layers:

1. **Inner loop (LiteRT automaticToolCalling)** — handles model→tool→model→tool cycles within a single `sendMessageAsync` call. When the model generates a `<|tool_call|>` token, LiteRT intercepts it, executes the `@Tool` method via reflection, injects the result, and lets the model continue. This all happens inside `runAgentTurn()`.

2. **Outer ReAct loop (ReflectAgent)** — handles the E4B failure mode where the model acknowledges without acting. After `runAgentTurn()` completes:
   - Each `@Tool` method increments `ToolCallTracker` as its first action
   - The loop checks `ToolCallTracker.get()` to see if tools were called
   - If tools called → model acted → accept response
   - If acknowledgment detected (regex: "I'll check", "Let me look", "Sure!") → force another turn
   - If skill instructions were injected but model ignored them → force another turn
   - Max 3 iterations, then fallback followup turn

### Execution Flow

1. **Model decides** — Gemma 4 processes the user message and system prompt, decides which tool(s) to call
2. **LiteRT invokes** — `automaticToolCalling = true` means LiteRT handles the tool invocation natively (no custom parsing)
3. **Tool executes** — The `@Tool` method increments `ToolCallTracker`, then runs its logic
4. **Result returns** — LiteRT feeds the tool result back to the model
5. **Model continues** — The model generates more tool calls or a text response
6. **Agent loop checks** — After `sendMessageAsync` completes, ReflectAgent checks: tools called? acknowledgment? substantive?
7. **UI updates** — `BorizonAction.Progress` events flow through the action channel to update the chat UI

### Safe Mode Shell Rules

The system prompt includes explicit rules about shell modes:
- **Safe mode (default)** — no pipes (`|`), redirects, or variables. Each call runs one command.
- **Filter first** — use `head -3 /proc/meminfo` instead of `cat /proc/meminfo | head -3`
- **Shell mode** — only for true pipelines that can't be decomposed

This was added after E4B kept generating piped commands in safe mode, where ProcessBuilder treats `|` as a literal argument, causing failures like `cat: Unknown option '3'`.
