---
name: travel-helper
description: Offline travel assistant with packing lists, itinerary planning, and local info.
triggers: "travel planning", "plan my trip", "vacation planner", "packing list", "itinerary planner"
---

# Travel Helper

Travel planning assistant that works offline — builds itineraries, packing lists, and saves trip details to memory.

## Steps (do all of these, then respond)

1. Call `memorySearch` with query "travel trip vacation flight hotel destination passport visa".
2. If web search is available, call `webSearch` for destination info. If not, use general knowledge.
3. Based on what the user asks:

### Packing List
- Ask: destination, duration, season/weather, type of trip (business/leisure/adventure)
- Generate a categorized packing list:
  - Essentials (passport, tickets, wallet, phone charger)
  - Clothing (based on weather and duration)
  - Toiletries
  - Electronics
  - Destination-specific (adapter type, insect repellent, etc.)
- Keep it short — max 15 items total

### Itinerary
- Ask: destination, dates, interests
- Build a day-by-day plan (max 5 days)
- Each day: 2-3 activities with morning/afternoon/evening structure
- If web available, suggest real places. Otherwise, suggest types of activities.

### Save Trip
- Call `memorySave` for each key trip detail:
  - Destination, dates, flight info → category EVENT, importance 0.8
  - Preferences (window seat, vegetarian hotel) → category PREFERENCE, importance 0.7
  - Packing list items they want to remember → category FACT, importance 0.5

## Response format

**Trip** — [destination, dates]
**Section** — [packing / itinerary / saved details]
**Content** — [the actual list or plan]
**Saved to memory** — [count of facts saved]

Keep responses scannable — use lists, not paragraphs. This is something they'll reference quickly while traveling.
