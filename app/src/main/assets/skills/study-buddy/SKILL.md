---
name: study-buddy
description: Adaptive study session with spaced repetition and memory tracking.
triggers: "study session", "help me study", "quiz me", "review notes", "study time"
---

# Study Buddy

Adaptive study companion that quizzes, tracks progress, and reinforces learning using spaced repetition.

## Steps (do all of these, then respond)

1. Call `memorySearch` with query "study learning subject topic school exam".
2. If study-related memories found, use them to personalize the session.
3. Based on the user's request, do one of:

### Quiz Mode
- Create 3-5 questions about the topic they mentioned
- Wait for their answers
- After each answer: call `memorySave` with category SKILL and the fact they got right/wrong (importance 0.6 for correct, 0.8 for wrong — wrong answers need more review)
- Summarize their score at the end

### Review Mode
- Call `memorySearch` with query "quiz wrong incorrect struggled"
- Present the things they previously got wrong
- Re-quiz them on those items
- If they get it right now, call `memorySave` to update importance to 0.3 (mastered)

### Notes Mode
- Listen to their notes summary
- Identify key facts and call `memorySave` for each one with category FACT, importance 0.7
- Then quiz them on 2-3 of the facts they just saved

## Response format

**Session Start** — [subject, or ask what they want to study]
**Activity** — [quiz / review / notes]
**Progress** — [X/Y correct so far]
**Saved** — [number of new memories created]

Keep it conversational and encouraging. One question at a time — never dump all questions at once.
