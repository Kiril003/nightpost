package local.phantom.companion.assistant

// Latin and Cyrillic names stay in separate literals: a mixed literal reads as UI copy to EnglishInUkrainianFirewallTest.
internal val ASSISTANT_NAME_LEAD = Regex(
    "^(?:" + listOf("nightpost", "phantom", "найтпост", "фантом").joinToString("|") + ")[,\\s]+",
)
