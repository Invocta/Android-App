package com.invocta.domain.skills

/**
 * Skill for web browsers (Chrome, Firefox, etc.)
 */
class BrowserSkill : BaseSkill() {
    override val id = "browser"
    override val name = "Web Browser Expert"
    override val description = "Expert knowledge for Chrome, Firefox, and other browsers"
    override val priority = 75

    override val triggerKeywords = listOf(
        "chrome", "browser", "firefox", "search", "google", "website",
        "web", "url", "http", "www", "browse", "internet", "open site",
        "form", "fill", "submit", "google form", "survey"
    )

    override val relevantApps = listOf(
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.opera.browser",
        "com.brave.browser",
        "com.microsoft.emmx",
        "com.UCMobile.intl"
    )

    override fun getInstructions(): String = """
**BROWSER WORKFLOW:**
1. open_app("Chrome") or your preferred browser
2. Find address/search bar (usually at top)
3. Click on it and input_text with URL or search query
4. Press enter/go: press_key("go") or click search button
5. Wait for page to load, then read_screen

**⚠️ CRITICAL: SCROLL TO SHOW USER WHAT YOU'RE DOING**
The user is watching! Before interacting with off-screen elements:
1. Check element Y coordinate in read_screen results
2. If Y > ~1800 (near bottom) or element from previous scroll: scroll_screen("down")
3. read_screen again to get updated positions
4. NOW interact with the visible element

**🍪 COOKIE CONSENT & POPUPS - HANDLE FIRST!**
When visiting ANY website, ALWAYS check for and dismiss these popups FIRST:
- **Cookie consent**: Look for "Accept", "Accept All", "Allow All", "Agree", "OK", "Got it", "I Accept"
- **Newsletter popups**: Look for "X", "Close", "No thanks", "Maybe later", "Dismiss"
- **Notification prompts**: Look for "Block", "Not now", "No thanks"
- **Login prompts**: Look for "X", "Close", "Skip", "Continue as guest"

**Cookie popup detection:**
When read_screen shows text containing: "cookie", "consent", "privacy", "GDPR", "accept all"
→ IMMEDIATELY click the accept/dismiss button BEFORE trying to interact with the page!

**COMMON COOKIE BUTTON TEXTS TO CLICK:**
- "Accept All", "Accept all cookies", "Accept All Cookies"
- "Allow All", "Allow all", "I Accept", "I Agree"
- "Accept", "Agree", "OK", "Got it", "Continue"
- "Accept & Continue", "Agree and proceed"
- "×" or "X" or "Close" (for dismissing)

**If cookie modal blocks the page:**
1. read_screen to identify the modal buttons
2. click_element on accept/close button (try text or description)
3. If click fails, use coordinates from read_screen
4. read_screen again to verify modal is gone
5. NOW proceed with the actual task

**DO NOT navigate("back") when you see a cookie popup!** 
Going back will just show the popup again. Dismiss it instead!

**⚠️ WEB FORMS - COMPLETE GUIDE:**

**TEXT INPUTS (NEVER GIVE UP!):**
- If input_text fails: scroll_screen("down") → read_screen → click field by coordinates → input_text
- Web text fields may need: click field TWICE, or click inside the field area
- Try field hint/placeholder text: input_text(text="...", field="Your answer")
- **NEVER skip text fields!** Keep trying: scroll, click, input until it works!

**RADIO BUTTONS & CHECKBOXES:**
- DO NOT use click_element(text="...") - web radio buttons don't expose text!
- Use toggle_switch(label="option text") - this works for web inputs
- If toggle_switch fails, use coordinates from read_screen

**DROPDOWNS / SELECT MENUS:**
- First: click on the dropdown to open it
- read_screen to see the options list
- click_element on the desired option
- OR use toggle_switch(label="option text")

**Finding Submit/Next buttons:**
- Buttons may be OFF SCREEN at bottom
- **ALWAYS scroll_screen("down") to find them**
- If button not found after read_screen → scroll down → read_screen again
- Use coordinates if text-based click fails

**FORM WORKFLOW (SCROLL AS YOU GO!):**
1. read_screen to check for cookie/popup modals first
2. **Dismiss any popups before proceeding!**
3. read_screen again to see form fields
4. **Scroll to first field** if needed
5. Fill text input: click field (by coordinates if needed) → input_text
6. **If input fails: scroll → click field again → retry input (DON'T GIVE UP!)**
7. Scroll down to next question
8. Select radio/checkbox: toggle_switch(label="option")
9. For dropdowns: click to open → click option
10. Continue scrolling and filling until Submit
11. Scroll to Submit → click

**SEARCHING THE WEB:**
- Click on address bar or search box
- input_text("your search query")
- press_key("search") or press_key("go")

**NAVIGATING PAGES:**
- scroll_screen("down") to see more content and SHOW USER what you're doing
- click_element on links (usually [Button] or clickable text)
- navigate("back") to go back (but NOT to dismiss popups!)

**PERSISTENCE RULES FOR WEB FORMS:**
- Text input failed? → scroll → click field again → retry (at least 3 times!)
- Can't find field? → scroll_screen("down") → read_screen
- **NEVER report "fields not accessible" without trying scroll + retry!**
- **Check if a popup is blocking the page!**
- Complete ALL fields before clicking Submit/Next
    """.trimIndent()
}

/**
 * Skill for media apps (YouTube, Spotify, etc.)
 */
class MediaSkill : BaseSkill() {
    override val id = "media"
    override val name = "Media Apps Expert"
    override val description = "Expert knowledge for YouTube, Spotify, music and video apps"
    override val priority = 75

    override val triggerKeywords = listOf(
        "youtube", "spotify", "music", "video", "play", "pause", "song",
        "movie", "netflix", "prime", "stream", "watch", "listen", "podcast"
    )

    override val relevantApps = listOf(
        "com.google.android.youtube",
        "com.spotify.music",
        "com.netflix.mediaclient",
        "com.amazon.avod.thirdpartyclient",
        "com.google.android.apps.youtube.music",
        "com.apple.android.music"
    )

    override fun getInstructions(): String = """
**MEDIA APP WORKFLOW:**
1. open_app with app name
2. Use search to find content
3. Click on search result to play
4. Use playback controls

**⚠️ CRITICAL: ICON BUTTONS USE description, NOT text!**
Media apps use icon buttons (magnifying glass, play, etc.) that have NO visible text.
- **ALWAYS use click_element(description="...") for icons, NOT click_element(text="...")**
- Example: Search icon → click_element(description="Search"), NOT click_element(text="Search")

**PLAYBACK CONTROLS:**
Most media apps have similar controls:
- Play/Pause: click_element(description="Play") or click_element(description="Pause")
- Next: click_element(description="Next")
- Previous: click_element(description="Previous")
- Volume: set_seekbar or device volume buttons

**YOUTUBE SPECIFIC:**

⚠️ **SEARCH WORKFLOW (VERY IMPORTANT):**
1. Click search: **click_element(description="Search")** ← Use description, NOT text!
2. Type query: input_text(text="your search query")
3. Press search: press_key("search")

⚠️ **AUTOCOMPLETE & SPELLING SUGGESTIONS:**
YouTube's autocomplete suggestions and "Did you mean: X" are NOT accessible via text!
- **Use read_screen_vision to capture screen with coordinate grid**
- Then click_element(x=..., y=...) with coordinates from the grid
- This is more reliable than retrying text-based clicks

**Other YouTube actions:**
- Play video: click on video thumbnail or title (use description if available)
- Fullscreen: click_element(description="Enter full screen")
- Like: click_element(description="like this video")

**SPOTIFY SPECIFIC:**
- Search: click_element(description="Search") on bottom tab, then tap search bar
- Play: tap on song or album  
- Shuffle: click_element(description="Shuffle")
- Like: click_element(description="Add to Liked Songs")

**TIPS:**
- Videos/songs appear as clickable elements
- Use scroll to see more results
- Playback controls usually at bottom of screen
- **When in doubt about icons → use description, not text**
- **For autocomplete/suggestions → use read_screen_vision + coordinate click**
    """.trimIndent()
}

/**
 * Skill for social media apps
 */
class SocialMediaSkill : BaseSkill() {
    override val id = "social_media"
    override val name = "Social Media Expert"
    override val description = "Expert knowledge for Instagram, Twitter/X, Facebook, etc."
    override val priority = 75

    override val triggerKeywords = listOf(
        "instagram", "twitter", "x", "facebook", "post", "share", "like",
        "comment", "follow", "story", "reel", "tweet", "feed", "profile",
        "snapchat", "tiktok", "linkedin", "reddit"
    )

    override val relevantApps = listOf(
        "com.instagram.android",
        "com.twitter.android",
        "com.facebook.katana",
        "com.snapchat.android",
        "com.zhiliaoapp.musically", // TikTok
        "com.linkedin.android",
        "com.reddit.frontpage"
    )

    override fun getInstructions(): String = """
**SOCIAL MEDIA COMMON ACTIONS:**

**Posting:**
1. Find new post button (usually + icon)
2. Select content type (photo, text, etc.)
3. Create content
4. Add caption if needed
5. Click Share/Post button

**Interactions:**
- Like: click_element(description="Like") or heart icon
- Comment: click_element(text="Comment") or comment icon
- Share: click_element(description="Share")
- Follow: click_element(text="Follow")

**INSTAGRAM:**
- Feed: bottom nav, home icon
- Search: magnifying glass icon
- New post: + icon at bottom
- Stories: tap profile pic or camera icon
- Reels: film icon at bottom

**TWITTER/X:**
- Tweet: + button or "What's happening"
- Like: heart icon
- Retweet: arrows icon
- Reply: speech bubble

**FACEBOOK:**
- Post: "What's on your mind?"
- Like: thumbs up
- Comment: comment bubble
- Share: share arrow

**TIPS:**
- Social apps update UI frequently - use read_screen to see current layout
- Icons may not have text labels - use description
- Scroll for more content in feeds
    """.trimIndent()
}

/**
 * Skill for productivity apps (Email, Calendar, Notes, etc.)
 */
class ProductivitySkill : BaseSkill() {
    override val id = "productivity"
    override val name = "Productivity Apps Expert"
    override val description = "Expert knowledge for Gmail, Calendar, Notes, and productivity apps"
    override val priority = 70

    override val triggerKeywords = listOf(
        "email", "gmail", "mail", "calendar", "event", "meeting", "schedule",
        "reminder", "note", "notes", "todo", "task", "outlook", "compose"
    )

    override val relevantApps = listOf(
        "com.google.android.gm",
        "com.google.android.calendar",
        "com.microsoft.office.outlook",
        "com.google.android.keep",
        "com.evernote",
        "com.todoist"
    )

    override fun getInstructions(): String = """
**EMAIL (Gmail/Outlook):**
1. Compose: click_element(description="Compose") or + button
2. To field: input_text with email address
3. Subject: click subject field, input_text
4. Body: click body area, input_text
5. Send: click_element(description="Send") or send icon

**Reading emails:**
- Tap on email to open
- Reply: click_element(text="Reply")
- Forward: click_element(text="Forward")
- Delete: click_element(description="Delete")

**CALENDAR:**
1. Add event: click + or "New event"
2. Fill in: Title, date, time, location
3. Save: click_element(text="Save")

**NOTES (Keep/Evernote):**
1. New note: click + or "New note"
2. Type content
3. Notes auto-save usually

**COMMON ELEMENTS:**
| App | Compose | Send |
|-----|---------|------|
| Gmail | Compose FAB | Send arrow (top right) |
| Outlook | New mail | Send arrow |
| Calendar | + button | Save |
| Keep | + button | Auto-saves |

**TIPS:**
- Email send button is usually top-right corner
- Calendar apps need date/time selection
- Look for floating action button (+) for new items
    """.trimIndent()
}

