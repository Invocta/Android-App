package com.invocta.domain.skills

/**
 * Skill for messaging apps (WhatsApp, Telegram, SMS, etc.)
 */
class MessagingSkill : BaseSkill() {
    override val id = "messaging"
    override val name = "Messaging Apps Expert"
    override val description = "Expert knowledge for WhatsApp, Telegram, SMS, and other messaging apps"
    override val priority = 80

    override val triggerKeywords = listOf(
        "whatsapp", "telegram", "message", "text", "sms", "send", "chat",
        "messenger", "signal", "viber", "dm", "direct message"
    )

    override val relevantApps = listOf(
        "com.whatsapp",
        "org.telegram.messenger",
        "com.google.android.apps.messaging",
        "com.facebook.orca",
        "org.thoughtcrime.securesms",
        "com.viber.voip",
        "com.discord",
        "com.slack"
    )

    override fun getInstructions(): String = """
**MESSAGING APP WORKFLOW:**
1. open_app with app name (WhatsApp, Telegram, etc.)
2. Find contact: look for search button or contact name in list
3. Open chat: click on contact name
4. Type message: click on input field first, then input_text
5. Send: click the Send BUTTON (not press_key!)
6. **VERIFY**: read_screen to confirm message appears in chat with checkmarks

**⚠️ CRITICAL: FAILURE RECOVERY FOR MESSAGING**
If input_text or click fails:
1. IMMEDIATELY call read_screen() to check what happened
2. Common issues: profile popup opened, wrong screen, keyboard dismissed
3. Dismiss any popups (click "Cancel", navigate back)
4. Make sure you're in the chat with input field visible
5. Click on input field explicitly before input_text

**⚠️ CRITICAL: SENDING MESSAGES**
- DO NOT use press_key("send") or press_key("enter") - these add newlines, not send!
- ALWAYS click the Send button directly:
  1. click_element(description="Send") - preferred for WhatsApp
  2. click_element(text="Send") - fallback
  3. Use coordinates of send button (right side of input)
- After clicking Send, VERIFY with read_screen:
  - Your message should appear in the chat
  - Look for checkmarks (✓✓) indicating delivery
  - If message still shows in input field, Send failed - retry!

**FINDING SEND BUTTON:**
The Send button varies by app:
- WhatsApp: contentDescription="Send" (paper plane icon on right)
- Telegram: contentDescription="Send" or text arrow button
- SMS: Usually "Send" text or arrow icon

**FINDING CONTACTS:**
- Look for search icon (magnifying glass) at top
- Or scroll through contact/chat list
- Contact names appear as clickable text elements

**COMMON ISSUES & RECOVERY:**
| Issue | Recovery Steps |
|-------|----------------|
| input_text fails | read_screen → click input field → retry input |
| Profile popup opens | read_screen → click Back or outside popup → return to chat |
| Can't find contact | Use search, spell name correctly |
| Send button doesn't work | Click by coordinates, it's usually right of input |
| Message not sent | read_screen to verify, click Send button again |

**VERIFICATION AFTER SENDING:**
After clicking Send, read_screen should show:
- Your message in the chat bubble (not in input field!)
- Delivery indicators (checkmarks ✓✓)
- Input field should be EMPTY
- If message still in input field = NOT SENT, click Send again!
    """.trimIndent()
}

