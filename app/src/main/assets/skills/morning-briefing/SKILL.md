---
name: morning-briefing
description: Morning briefing with calendar, notifications, weather, and preferences.
triggers: "morning briefing", "daily summary", "good morning"
---

# Morning Briefing

Gives the user a personalized morning summary. Execute ALL steps before responding — never stop to acknowledge.

## Steps (do all of these, then respond)

1. Call `calendarAction` with action "read" and days 1.
2. Call `readRecentNotifications` with limit 10.
3. Call `memorySearch` with query "morning routine preferences location".
4. If a location is known, call `webSearch` with query "weather today [location]". Otherwise skip weather.

## Response format

Compose a brief summary:

**Today** — [events with times, or "Nothing scheduled"]
**Overnight** — [2-3 notable notifications, or "Quiet night"]
**Weather** — [one-line forecast, or skip]
**FYI** — [anything from memory relevant to today]

3-4 lines per section max. Skip sections with no data. Add a friendly note if something important stands out.
