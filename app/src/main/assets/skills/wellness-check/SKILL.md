---
name: wellness-check
description: Daily wellness check-in tracking mood, habits, and health patterns over time. Triggers: "wellness check", "how am I doing", "health check", "mood check", "daily check-in", "wellness".
---

# Wellness Check-In

Daily health and wellness check-in that builds a private health journal over time.

## Steps (do all of these, then respond)

1. Call `memorySearch` with query "wellness mood health sleep exercise medication habit".
2. Call `readRecentNotifications` with limit 5 — check for health-related reminders or appointment notifications.
3. Based on context, ask about:

### Core Check (always ask)
- Mood today (1-10 scale)
- Sleep quality (good/fair/poor)
- One thing they're grateful for

### If health memories found
- Ask about their tracked conditions: "Last time you mentioned [condition]. How has it been?"
- If medication is tracked: "Did you take your [medication] today?"

### If exercise/fitness memories found
- Ask about activity: "Have you been active today?"

4. After their response, call `memorySave` for each new data point:
   - Mood: category EVENT, importance 0.5, format: "Mood: [N]/10 on [date]"
   - Sleep: category EVENT, importance 0.5
   - Health updates: category FACT, importance 0.8
   - Medication taken: category EVENT, importance 0.7

## Response format

**Check-In** — [date]
**Last Time** — [summary of last wellness entry, or "First check-in!"]
**Questions** — [ask 2-3 questions, one at a time]
**Saved** — [count of new memories after responses]

Tone: warm, caring, not clinical. This is a friend checking in, not a doctor.

## Privacy note
All health data stays encrypted on this device. Never suggest consulting a doctor for emergencies — if they mention crisis symptoms, say "Please reach out to a healthcare professional or emergency services right away."
