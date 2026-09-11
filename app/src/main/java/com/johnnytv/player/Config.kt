package com.johnnytv.player

/**
 * WHITE-LABEL CONFIG
 *
 * Everything you change per build lives here (plus app_name in strings.xml, the
 * colours in colors.xml, the icons in res/mipmap-*, and applicationId in
 * app/build.gradle.kts).
 */
object Config {

    /**
     * THE IMPORTANT ONE.
     *
     * A small JSON file you host. The app reads it every time it starts, so you can
     * change the portal address, post a message, or announce an update WITHOUT
     * rebuilding the app or asking anyone to reinstall.
     *
     * Free to host: put config.json in a PUBLIC GitHub repo and use its raw URL.
     * Leave blank and the app falls back to asking the user for a server address.
     */
    const val CONFIG_URL: String =
        "https://raw.githubusercontent.com/johnnytv-net/johnnytv-config/main/config.json"

    /**
     * Every service this build accepts logins for, best guess first.
     *
     * A customer types only a username and password; the app works out which of
     * these knows them and remembers it for next time. Values may be plain
     * addresses or the base64 form. Used when CONFIG_URL is unreachable, and
     * merged with whatever config.json lists.
     */
    val SERVERS: List<String> = listOf(
        "aHR0cDovL2VkZ2UuYno6ODA4MA==",
        "http://line.dino.ws"
    )

    /** Kept for older builds; SERVERS is the list that matters now. */
    const val DEFAULT_SERVER: String = ""

    /** Set both for a build that skips the login screen entirely. Normally left blank. */
    const val PRESET_USERNAME: String = ""
    const val PRESET_PASSWORD: String = ""

    /**
     * Buffering.
     *
     * BUFFER_FOR_PLAYBACK is the only one that affects how fast a channel opens:
     * it's how many milliseconds must be downloaded before the picture starts.
     * MIN/MAX are how far ahead the player keeps reading once it's running -
     * raise those if customers report stuttering on weaker connections.
     */
    const val MIN_BUFFER_MS: Int = 15_000
    const val MAX_BUFFER_MS: Int = 60_000
    const val BUFFER_FOR_PLAYBACK_MS: Int = 800
    const val BUFFER_AFTER_REBUFFER_MS: Int = 2_500

    /**
     * Live streams are tried in this order until one plays.
     *
     * .ts is the portal's raw feed and starts almost immediately. .m3u8 wraps the
     * same feed in a playlist the player must fetch and then read several chunks
     * of before it can show anything, which costs several seconds on open - so it
     * sits second, as the fallback for channels that only publish HLS.
     */
    val LIVE_CONTAINERS: List<String> = listOf("ts", "m3u8")

    /**
     * Which Live TV category to open on.
     *
     * Most portals list their pay-per-view and event placeholders first, so the
     * full channel list opens on a screen full of "PPV" tiles. Name the categories
     * you would rather people land on, best first: the app takes the earliest one
     * that actually exists on the portal, preferring an exact name match over a
     * partial one, and falls back to the full list if none of them are there.
     * Leave the list empty to always open on All.
     */
    val PREFERRED_LIVE_CATEGORIES: List<String> = listOf("SPORTS", "ENGLISH")

    /**
     * How the Live TV category list is ordered.
     *
     * Portals list their categories in whatever order suits them, which usually
     * means pay-per-view and event blocks near the top. Anything whose name
     * contains a word from CATEGORY_FIRST is lifted to the top, in the order
     * written here, then the CATEGORY_EVENTS blocks, then everything the portal
     * sent in its own order, and CATEGORY_LAST at the very bottom.
     *
     * The events used to be buried at the bottom, which is right for a portal
     * that opens on a wall of pay-per-view placeholders - but wrong once the
     * good categories are pinned above them. Somebody looking for a fight card
     * is looking for it near the sport.
     */
    val CATEGORY_FIRST: List<String> = listOf("ENGLISH", "SPORTS")
    val CATEGORY_EVENTS: List<String> = listOf("PPV", "PAY PER VIEW", "WWE")

    /** Last wherever it is found, so nobody lands on it by scrolling. */
    val CATEGORY_LAST: List<String> = listOf("ADULT", "XXX", "18+", "FOR ADULTS")

    const val USER_AGENT: String = "JohnnyTV/4.7 (Android)"
}
