package com.borizon.app.ai.prompts

/**
 * Starter prompts for the Borizon phone assistant.
 * Clicking a card populates the input field — user edits before sending.
 */
enum class StarterTemplate(
    val label: String,
    val description: String,
    val accentColor: Long,
    val examplePrompts: List<String>,
) {
    REMEMBER(
        label = "Remember",
        description = "Save a fact, preference, or detail",
        accentColor = 0xFF2DD4BF,
        examplePrompts = listOf(
            "Remember that I'm vegetarian",
            "Remember my doctor's appointment is June 15",
            "Note that I prefer meetings in the afternoon",
        )
    ),
    SCHEDULE(
        label = "Schedule",
        description = "Calendar, alarms, and timers",
        accentColor = 0xFF22D3EE,
        examplePrompts = listOf(
            "What's on my calendar today?",
            "Set an alarm for 7am tomorrow",
            "Set a timer for 10 minutes",
        )
    ),
    CONNECT(
        label = "Connect",
        description = "Call, text, email, and contacts",
        accentColor = 0xFF3B82F6,
        examplePrompts = listOf(
            "Call Mom",
            "Text John I'm running late",
            "Add Sarah as a contact, 555-1234",
        )
    ),
    CONTROL(
        label = "Control",
        description = "Apps, settings, and shell",
        accentColor = 0xFFF59E0B,
        examplePrompts = listOf(
            "Open Spotify",
            "Open WiFi settings",
            "How much storage do I have left?",
        )
    ),
    EXPLORE(
        label = "Explore",
        description = "Web search and browsing",
        accentColor = 0xFF22C55E,
        examplePrompts = listOf(
            "Search the web for weather this weekend",
            "Read this article: [URL]",
            "Search for flights to Tokyo",
        )
    ),
    RECALL(
        label = "Recall",
        description = "Search memories and notifications",
        accentColor = 0xFFFB7185,
        examplePrompts = listOf(
            "What do you know about me?",
            "What are my latest notifications?",
            "Search my memories for diet preferences",
        )
    );

    companion object {
        val DEFAULT = REMEMBER
    }
}
