package com.invocta.domain.skills

/**
 * Registry that manages all available skills and selects relevant ones
 * based on the current context.
 */
class SkillRegistry {
    private val skills = mutableListOf<Skill>()

    /**
     * Register a skill
     */
    fun register(skill: Skill) {
        skills.add(skill)
    }

    /**
     * Register multiple skills
     */
    fun registerAll(vararg skillList: Skill) {
        skills.addAll(skillList)
    }

    /**
     * Get all registered skills
     */
    fun getAllSkills(): List<Skill> = skills.toList()

    /**
     * Find skills relevant to the given query and context
     * Returns skills sorted by priority (highest first)
     */
    fun findRelevantSkills(
        userQuery: String,
        currentApp: String? = null,
        maxSkills: Int = 3
    ): List<Skill> {
        return skills
            .filter { it.isRelevant(userQuery, currentApp) }
            .sortedByDescending { it.priority }
            .take(maxSkills)
    }

    /**
     * Get combined instructions from all relevant skills
     */
    fun getSkillInstructions(
        userQuery: String,
        currentApp: String? = null,
        maxSkills: Int = 3
    ): String {
        val relevantSkills = findRelevantSkills(userQuery, currentApp, maxSkills)

        if (relevantSkills.isEmpty()) {
            return ""
        }

        return buildString {
            appendLine("\n## ACTIVE SKILLS")
            appendLine("The following specialized knowledge is available for this task:\n")

            relevantSkills.forEach { skill ->
                appendLine("### ${skill.name}")
                appendLine(skill.getInstructions())
                appendLine()
            }
        }
    }

    /**
     * Get skill by ID
     */
    fun getSkillById(id: String): Skill? = skills.find { it.id == id }

    companion object {
        /**
         * Create a registry with all default skills
         */
        fun createDefault(): SkillRegistry {
            return SkillRegistry().apply {
                // Core skills
                register(CoreAndroidSkill())
                register(PersistenceSkill())

                // App-specific skills
                register(MessagingSkill())
                register(SettingsSkill())
                register(BrowserSkill())
                register(MediaSkill())
                register(SocialMediaSkill())
                register(ProductivitySkill())
            }
        }
    }
}

