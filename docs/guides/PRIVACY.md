# Borizon — Privacy & Security

**Privacy guarantees and security architecture for Borizon.**

---

## Privacy Manifesto

1. **100% on-device** — AI model, inference, memory, conversations — everything runs locally. No cloud processing.
2. **Encrypted storage** — All data encrypted at rest with SQLCipher + Android Keystore.
3. **Biometric lock** — Mandatory authentication on every app launch.
4. **No accounts** — No sign-up, no email, no server. Tied to device only.
5. **No telemetry** — Zero analytics, crash reporting, or usage tracking.
6. **Opt-in web** — Web search requires manual API key configuration. Works fully offline without it.

---

## Threat Model

| Threat | Protection |
|--------|-----------|
| **Device theft** | Biometric lock + SQLCipher encryption. Data inaccessible without biometric. |
| **App decompilation** | ProGuard + R8 minification in release builds. No secrets in code (API keys in DataStore). |
| **Network interception** | No data transmitted during normal use. Web search over HTTPS only. |
| **Root access** | SQLCipher passphrase encrypted with hardware-backed Keystore key (non-extractable even with root). |
| **Backup extraction** | Database not included in Android Auto Backup. |
| **Notification snooping** | Sensitive app content (banking, auth) automatically hidden. |

---

## Data at Rest

### Database Encryption

All persistent data stored in `borizon.db`, encrypted with SQLCipher:

```
Android Keystore (hardware-backed, TEE/StrongBox)
  └── AES-256-GCM key (non-extractable)
       └── Encrypts 512-bit SQLCipher passphrase
            └── Stored as Base64(ciphertext + IV) in SharedPreferences
```

**Key properties:**
- The SQLCipher passphrase itself is encrypted — even if `SharedPreferences` is compromised, the raw key is not available
- The Keystore key lives in the device's Trusted Execution Environment (TEE) or StrongBox — cannot be extracted even with root
- Key invalidation (biometric enrollment change, lock screen removal) triggers graceful data loss with user notification

### What's Stored

| Data | Encrypted | Retention | Notes |
|------|-----------|-----------|-------|
| Conversations | ✅ SQLCipher | Until user deletes | Thread metadata + summaries |
| Messages | ✅ SQLCipher | Until user deletes | Full message history |
| Memories | ✅ SQLCipher | Until user deletes or prune | Max 500 entries, importance-ranked |
| Notifications | ✅ SQLCipher | 24 hours | Auto-expired, sensitive content hidden |
| Reflections | ✅ SQLCipher | Until user deletes | Thinking session entries |
| Preferences | ✅ Proto DataStore | Persistent | Model choice, API key, settings |
| Model file | Unencrypted | Persistent | Downloaded to app files dir |
| Audio clips | Unencrypted | Session-only | WAV files, deleted when session ends |
| Images | In-memory only | Session-only | Bitmaps, never persisted |

---

## Data in Transit

### Normal Operation

**Zero network activity.** The app has `INTERNET` permission but only uses it for:

1. **Model download** — First-launch download of `gemma-4-E2B-it.litertlm` (~2.6 GB) or `gemma-4-E4B-it.litertlm` (~3.7 GB) from HuggingFace over HTTPS
2. **Web search** — Optional, opt-in. Queries sent to Brave Search API over HTTPS. Requires user to manually configure their own API key.

Once the model is downloaded, the app works fully offline. All inference, memory, and tool execution happens on-device.

### Network Permissions

The `INTERNET` permission is declared in `AndroidManifest.xml` for model download and opt-in web search. It could be removed for an offline-only build.

---

## Shell Security

The shell is Borizon's most powerful — and most carefully secured — tool. It gives the model real command-line access to the phone.

### Dual-Mode Execution

| Mode | When | How | Security |
|------|------|-----|----------|
| **Safe** (default) | Direct binary execution | `ProcessBuilder` with binary path | Binary allowlist (60+ tools) |
| **Shell** | Pipes, redirects needed | `sh -c` with full command | Pattern denylist (60+ dangerous) |

### Safe Mode Allowlist (selected)

File ops: `ls`, `cat`, `head`, `tail`, `wc`, `sort`, `grep`, `find`, `mkdir`, `cp`, `mv`, `stat`, `du`, `df`

System info: `uname`, `uptime`, `getprop`, `top`, `ps`, `free`, `id`

Android: `pm`, `am`, `dumpsys`, `settings`

Network info (read-only): `ifconfig`, `ip`, `netstat`

### Shell Mode Denylist (patterns)

Privilege escalation: `su`, `sudo`

Destructive: `rm -rf`, `rm -r`, `dd`, `mkfs`, `format`

Permission: `chmod`, `chown`

Remote: `curl`, `wget`, `ssh`, `scp`, `nc`, `telnet`

Code exec: `python`, `perl`, `ruby`, `sh -c` (nested)

### Universal Constraints

Both modes enforce:
- **15-second timeout** — process killed after timeout
- **80-line / 8000-char output cap** — prevents memory exhaustion
- **Environment scrubbing** — only PATH, HOME, USER, TERM passed
- **No stdin** — no interactive input possible
- **Single-execution mutex** — only one command at a time
- **Dedicated working directory** — not `/` or `/sdcard` root

---

## Notification Privacy

Borizon captures notifications via `NotificationListenerService` for AI awareness.

**Privacy protections:**
- **Sensitive app content hiding** — Notifications from banking, authentication, and 2FA apps have their text content automatically replaced with `[content hidden]`
- **24-hour auto-expiry** — All notifications deleted from database after 24 hours
- **User control** — Notification listener can be disabled in system settings
- **No forwarding** — Notification content never leaves the device

---

## Skill Sandboxing

JavaScript skills run in a tightly controlled WebView environment:

| Constraint | Enforcement |
|-----------|-------------|
| **No external network** | `WebViewAssetLoader` loads local assets only |
| **60-second timeout** | WebView destroyed after timeout |
| **No file access** | WebView has no file:// access |
| **Single entry point** | `borizon_skill_execute(data)` only |
| **Explicit callback** | `BorizonBridge.onResultReady()` to return results |
| **WebView destroyed** | WebView reference nullified after skill completes |

---

## Third-Party Services

| Service | Purpose | Data Sent | Opt-in? |
|---------|---------|-----------|---------|
| **HuggingFace** | Model download | No personal data, just downloads `.litertlm` file | No (required for first launch) |
| **Brave Search** | Web search | Search queries only, over HTTPS | Yes — requires manual API key |
| **LiteRT** | On-device inference | No data sent — runs entirely locally | N/A (on-device) |

No other external services. No Firebase, no Google Analytics, no Mixpanel, no Sentry.

---

## Open Source Dependencies

Key dependencies with security implications:

| Dependency | Purpose | Security |
|-----------|---------|----------|
| SQLCipher | Database encryption | Industry-standard, audited |
| LiteRT-LM | On-device inference | Google-maintained |
| Hilt | Dependency injection | Google-maintained |
| Room | Database ORM | AndroidX, Google-maintained |
| OkHttp | HTTP client (web search) | Square, widely audited |
| halilibo richtext | Markdown rendering | UI-only, no network |
