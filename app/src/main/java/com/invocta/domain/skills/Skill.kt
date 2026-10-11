package com.invocta.domain.skills

/**
 * Represents a skill that provides domain-specific knowledge and strategies
 * to the AI agent. Skills help the agent perform tasks in specific contexts
 * (like messaging apps, settings, browsers, etc.) more effectively.
 */
interface Skill {
    /**
     * Unique identifier for this skill
     */
    val id: String

    /**
     * Human-readable name of the skill
     */
    val name: String

    /**
     * Brief description of what this skill enables
     */
    val description: String

    /**
     * Keywords that help match this skill to user requests
     * e.g., ["whatsapp", "message", "text", "send"] for messaging skill
     */
    val triggerKeywords: List<String>

    /**
     * App package names this skill is relevant for
     * e.g., ["com.whatsapp", "com.google.android.apps.messaging"] for messaging
     */
    val relevantApps: List<String>

    /**
     * Priority level (higher = more specific/preferred)
     * General skills have low priority, app-specific skills have high priority
     */
    val priority: Int get() = 50

    /**
     * The instructions/knowledge this skill provides to the agent.
     * This gets injected into the system prompt when the skill is active.
     */
    fun getInstructions(): String

    /**
     * Check if this skill is relevant for the given context
     */
    fun isRelevant(userQuery: String, currentApp: String? = null): Boolean {
        val queryLower = userQuery.lowercase()
        val keywordMatch = triggerKeywords.any { queryLower.contains(it.lowercase()) }
        val appMatch = currentApp != null && relevantApps.any {
            currentApp.contains(it, ignoreCase = true)
        }
        return keywordMatch || appMatch
    }
}

/**
 * Base implementation with common functionality
 */
abstract class BaseSkill : Skill {
    override fun isRelevant(userQuery: String, currentApp: String?): Boolean {
        val queryLower = userQuery.lowercase()
        val keywordMatch = triggerKeywords.any { keyword ->
            queryLower.contains(keyword.lowercase())
        }
        val appMatch = currentApp != null && relevantApps.any { app ->
            currentApp.equals(app, ignoreCase = true)
        }
        return keywordMatch || appMatch
    }
}

