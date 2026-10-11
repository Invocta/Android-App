package com.invocta.domain.skills

/**
 * Core Android navigation and problem-solving skill.
 * This is always active and provides fundamental strategies.
 */
class CoreAndroidSkill : BaseSkill() {
    override val id = "core_android"
    override val name = "Android Expert"
    override val description = "Fundamental Android navigation and UI interaction expertise"
    override val priority = 100 // Always highest priority

    override val triggerKeywords = emptyList<String>() // Always active for screen control
    override val relevantApps = emptyList<String>()

    // This skill is always relevant when tools are enabled
    override fun isRelevant(userQuery: String, currentApp: String?): Boolean = true

    override fun getInstructions(): String = """
**CORE PRINCIPLES:**
- NEVER give up easily - if one approach fails, try 2-3 alternatives
- ALWAYS complete the user's task - don't stop until done or truly impossible  
- Be resourceful - use your Android knowledge to find creative solutions
- Verify your work - read_screen after actions to confirm success
- **SHOW THE USER what you're doing - scroll to make elements visible!**

**⚠️ CRITICAL: ICON vs TEXT BUTTONS**
When clicking UI elements, know the difference:
- **Text buttons** (visible label): Use click_element(text="Sign in")
- **Icon buttons** (magnifying glass, send, back, etc.): Use click_element(description="Search")
- **RULE:** If you see an icon with no text label → use description, NOT text!
- Common icon descriptions: "Search", "Send", "Back", "More options", "Menu"

**⚠️ AUTOCOMPLETE & SUGGESTIONS:**
Search autocomplete, spelling suggestions, and dropdown items often have POOR accessibility.
- If click_element fails on a suggestion → use read_screen_vision to get coordinates
- read_screen_vision shows a coordinate grid overlay for precise tapping
- Then click_element(x=..., y=...) with the coordinates from the grid

**⚠️ CRITICAL: SCROLL TO SHOW USER**
The user is watching the screen! Before interacting with elements:
1. Check if element is visible (Y coordinate < ~1800)
2. If element is below viewport → scroll_screen("down") FIRST
3. read_screen to get new positions
4. NOW interact with the visible element

**WORKFLOW:**
1. read_screen → see what's on screen
2. **Check for popups/modals first and dismiss them!**
3. Identify target element by text, description, or coordinates
4. **If element is off-screen → scroll_screen to bring it into view**
5. Perform action (click, toggle, input, etc.)
6. read_screen again to verify success
7. If failed, try alternative approaches

**🍪 POPUP DETECTION - CHECK FIRST ON WEB PAGES:**
Before interacting with a web page, check if these block your view:
- Cookie consent banners: Look for "Accept", "Accept All", "Allow All"
- Privacy notices: Look for "Agree", "OK", "Continue", "Got it"
- Newsletter/Login popups: Look for "X", "Close", "Skip", "No thanks"
→ **Dismiss these BEFORE trying to fill forms or click elements!**

**⚠️ FAILURE RECOVERY - MANDATORY:**
When ANY tool returns an error:
1. IMMEDIATELY call read_screen() to check current state
2. Identify what changed (popup? wrong screen? element gone?)
3. Handle the unexpected state (dismiss popup, go back, etc.)
4. Once on correct screen, retry the action
5. **If clicking fails → try description instead of text, or vice versa**
6. **If 2 clicks fail on same element → switch to vision mode with coordinates**

**ELEMENT IDENTIFICATION:**
- [Button] = clickable, use click_element
- [Toggle:ON/OFF] = switch, use toggle_switch or click coordinates
- [Input] = text field, use input_text
- [Slider:X%] = seekbar, use set_seekbar
- [Scrollable] = can scroll for more content
    """.trimIndent()
}

/**
 * Skill for persistence and not giving up
 */
class PersistenceSkill : BaseSkill() {
    override val id = "persistence"
    override val name = "Task Completion Expert"
    override val description = "Strategies for completing tasks despite obstacles"
    override val priority = 90

    override val triggerKeywords = emptyList<String>()
    override val relevantApps = emptyList<String>()

    override fun isRelevant(userQuery: String, currentApp: String?): Boolean = true

    override fun getInstructions(): String = """
**⚠️ CRITICAL FAILURE RECOVERY RULE:**
**Tool fails → read_screen() FIRST → then decide next action**

Never retry a failed action without checking screen state!

**FAILURE SCENARIOS & RECOVERY:**
| Failure Type | Recovery Steps |
|--------------|----------------|
| input_text fails | scroll → read_screen → click field by coordinates → retry input |
| click_element(text) fails | Element might be an icon - try description, or use tap_with_vision |
| click_element on suggestion | Use tap_with_vision(target="the suggestion text") |
| Element not found | scroll_screen("down") → read_screen → find element → retry |
| Wrong screen | read_screen → navigate back → verify correct screen → retry |
| Popup blocking | read_screen → find dismiss/accept button → click it → retry original action |

**⚠️ AUTOCOMPLETE / SUGGESTIONS - USE tap_with_vision:**
Search autocomplete, spelling suggestions ("Did you mean..."), and dropdown options 
typically have POOR accessibility. Don't waste iterations retrying text clicks!
- **Use tap_with_vision(target="first suggestion") - single fast operation!**
- This applies to: YouTube suggestions, Google search, any autocomplete dropdown
- tap_with_vision is FASTER than read_screen_vision + click_element

**🍪 POPUP/MODAL BLOCKING DETECTION:**
If an action fails and read_screen shows ANY of these, dismiss the popup FIRST:
- Cookie consent: "Accept", "Accept All", "Allow", "Agree", "OK", "Got it"
- Privacy notices: "I Accept", "Continue", "Dismiss"
- Newsletter: "X", "Close", "No thanks", "Skip"
- Login prompts: "Skip", "Close", "Not now", "Continue as guest"
- Captcha/Verification: May need user help

**⚠️ DO NOT use navigate("back") to dismiss popups on web pages!**
This will reload the page and show the popup again. Click the dismiss button instead!

**⚠️ WEB FORM TEXT FIELDS - NEVER GIVE UP!**
If input_text fails on a web form:
1. Check if a popup/modal is blocking (cookie consent, etc.)
2. If popup exists → dismiss it first!
3. scroll_screen("down") to ensure field is visible
4. read_screen to get field coordinates
5. click_element with coordinates to focus the field
6. Try input_text again
7. If still fails, click inside the field area again
8. **Repeat at least 3 times before giving up!**

**PERSISTENCE STRATEGIES (after read_screen confirms state):**
- Try 3 different approaches before reporting failure
- Alternative selectors: text → description → read_screen_vision + coordinates
- **For icons: ALWAYS prefer description over text**
- **For autocomplete/suggestions: use read_screen_vision → click with coordinates**
- If element moved, get new coordinates from read_screen
- If popup blocks action, dismiss it first
- **For web forms: scroll → click → input (repeat until success!)**

**COMMON POPUP DISMISSALS:**
- Cookie: "Accept All", "Accept", "Allow All", "I Agree", "Got it"
- Dialogs: "Cancel", "Not now", "Skip", "OK", "Got it"
- Close buttons: "X", "×", "Close", or tap outside
- navigate("back") for Android dialogs (NOT for web popups!)

**COMPLETION CHECKLIST:**
- Did read_screen confirm the action succeeded?
- Is the user's goal achieved?
- If not, what changed on screen that needs handling?
- **Is there a popup blocking the view?**
- **For forms: Did you fill ALL required fields?**
    """.trimIndent()
}

