---
name: phone-status
description: Device health check — battery, storage, memory.
triggers: "how is my phone", "phone status", "device status", "phone health", "status check"
---

# Phone Status Check

Quick snapshot of device health.

## Actions

### check
**When**: User asks about phone status, device health, storage, battery, or "how's my phone".
**How**:
1. Call `shellExecute` with command `dumpsys battery` mode `safe`.
2. Call `shellExecute` with command `df -h /sdcard /data` mode `safe`.
3. Call `shellExecute` with command `head -3 /proc/meminfo` mode `safe`.

Then summarize in 2-3 sentences:
- Battery: level, charging/not, temperature (note if >40°C)
- Storage: how full /sdcard is (note if >90%)
- Memory: how much RAM available
- Skip any that failed.

**Rules**:
- Exactly 3 shellExecute calls, no more. No other tools needed.
- Do NOT use pipes (|). Put filter commands first: `head -3 /path` not `cat /path | head -3`.
- Keep response short — a glance, not a report.
