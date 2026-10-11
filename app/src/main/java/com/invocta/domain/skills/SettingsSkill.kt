package com.invocta.domain.skills

/**
 * Skill for Android Settings navigation
 */
class SettingsSkill : BaseSkill() {
    override val id = "settings"
    override val name = "Settings Expert"
    override val description = "Expert knowledge for Android Settings app navigation"
    override val priority = 80

    override val triggerKeywords = listOf(
        "settings", "setting", "turn on", "turn off", "enable", "disable",
        "dark mode", "dark theme", "wifi", "wi-fi", "bluetooth", "brightness",
        "volume", "sound", "notification", "battery", "display", "network",
        "airplane", "location", "nfc", "hotspot", "mobile data"
    )

    override val relevantApps = listOf(
        "com.android.settings",
        "com.samsung.android.app.settings",
        "com.oneplus.settings"
    )

    override fun getInstructions(): String = """
**SETTINGS NAVIGATION:**
1. open_app("Settings") to open Settings
2. Use read_screen to see available options
3. Click on category (Display, Network, Sound, etc.)
4. Find the specific setting
5. Use toggle_switch for on/off settings
6. Use set_seekbar for sliders (brightness, volume)

**COMMON SETTINGS PATHS:**
| Setting | Path |
|---------|------|
| Dark mode | Display → Dark theme |
| Wi-Fi | Network & internet → Wi-Fi |
| Bluetooth | Connected devices → Bluetooth |
| Brightness | Display → Brightness |
| Volume | Sound → Volume |
| Do Not Disturb | Sound → Do Not Disturb |
| Mobile data | Network & internet → Mobile network |
| Hotspot | Network & internet → Hotspot |
| Location | Location |
| Battery saver | Battery → Battery saver |

**TOGGLING SETTINGS:**
- For switches: toggle_switch(label="Setting Name")
- If that fails: click on the setting text to toggle
- Or use coordinates from read_screen
- Look for [Toggle:ON] or [Toggle:OFF] in screen content

**ADJUSTING SLIDERS:**
- For brightness: set_seekbar(value=50, label="Brightness")
- For volume: set_seekbar(value=75, label="Volume")
- Use adjust parameter for relative changes: set_seekbar(adjust=-20)

**SEARCH IN SETTINGS:**
If you can't find a setting:
1. Look for search icon at top
2. Click and type the setting name
3. Select from search results

**VERIFICATION:**
After changing a setting:
- read_screen to confirm new state
- Toggle should show [Toggle:ON] or [Toggle:OFF]
- Slider should show new percentage
    """.trimIndent()
}

