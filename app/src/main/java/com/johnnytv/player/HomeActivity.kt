package com.johnnytv.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var clock: TextView
    private lateinit var today: TextView

    private var featured: SeriesItem? = null

    private val handler = Handler(Looper.getMainLooper())

    /**
     * The weather line, fetched in the background and drawn only if it arrives.
     *
     * Never blocks anything: the home screen is complete without it, and a
     * customer with a portal but no general internet access still gets an app
     * that works rather than one that sits waiting on a weather service.
     */
    /**
     * THE NEWSPAPER, BEHIND EVERYTHING.
     *
     * The same page the website signs people in with, shown here rather than
     * rebuilt: the saucer bobbing, the beam breathing, the lights going round,
     * people riding it up. Every one of those timelines was written once, for
     * the site, and this way the two cannot drift apart.
     *
     * Bundled inside the app rather than fetched, so it is there before anyone
     * signs in and on a box with no internet beyond the portal.
     *
     * A plain picture of the front page goes up first and stays underneath. If
     * the animated one arrives it covers it; if it never does, nobody sees a
     * failure, they see a newspaper.
     */
    private fun showBackdrop() {
        val prefs = Prefs(this)
        val backdrop = findViewById<android.webkit.WebView>(R.id.homeBackdrop)
        val still = findViewById<android.widget.ImageView>(R.id.homeBackdropStill)
        val scrim = findViewById<View>(R.id.homeScrim)

        if (!prefs.newspaperBackground) {
            backdrop.visibility = View.GONE
            still.visibility = View.GONE
            scrim.visibility = View.GONE
            return
        }

        still.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE

        backdrop.settings.javaScriptEnabled = true
        backdrop.settings.domStorageEnabled = true
        backdrop.settings.allowFileAccess = true
        backdrop.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        backdrop.isFocusable = false
        backdrop.isFocusableInTouchMode = false
        backdrop.setOnTouchListener { _, _ -> true }
        backdrop.overScrollMode = View.OVER_SCROLL_NEVER
        backdrop.isVerticalScrollBarEnabled = false
        backdrop.isHorizontalScrollBarEnabled = false

        backdrop.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                // Hide the sign-in box and let the scene fill the screen, done
                // here rather than by editing the page - so the file stays the
                // one the website itself serves.
                view?.evaluateJavascript(
                    "(function(){var s=document.createElement('style');" +
                        "s.textContent='#modal{display:none!important}" +
                        "html,body{overflow:hidden!important}" +
                        ".paper{height:100vh!important;max-width:none!important}';" +
                        "document.head.appendChild(s);})();",
                    null
                )
                backdrop.visibility = View.VISIBLE
            }

            override fun onReceivedError(
                view: android.webkit.WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                backdrop.visibility = View.GONE
            }
        }

        runCatching { backdrop.loadUrl("file:///android_asset/newspaper.html") }
            .onFailure { backdrop.visibility = View.GONE }

        // A box with no usable web view can sit loading for ever. Five seconds
        // is fair to a Firestick and short enough that nobody wonders what
        // went wrong - the still is already there either way.
        handler.postDelayed({
            if (!isFinishing && backdrop.visibility != View.VISIBLE) {
                backdrop.visibility = View.GONE
            }
        }, 5_000L)
    }

    private fun showWeather() {
        val cached = Weather.lastKnown()
        if (cached != null) {
            drawWeather(cached)
            return
        }
        kotlin.concurrent.thread {
            val now = runCatching { Weather.fetch(this) }.getOrNull() ?: return@thread
            handler.post { if (!isFinishing) drawWeather(now) }
        }
    }

    /**
     * When the line runs out, on the home screen.
     *
     * The one piece of account information worth a customer seeing without
     * going to look for it - a line that lapses on a Friday night is a phone
     * call either way, and a fortnight's warning turns it into a renewal
     * instead. Shown as a date rather than a countdown, because "20 Dec" is
     * something somebody can act on and "63 days" is not.
     *
     * Yellow throughout rather than only at the end: a renewal date is worth
     * seeing every day, and a colour that appears in the last week is a colour
     * nobody has learned to read by the time it matters.
     */
    private fun showExpiry() {
        val label = findViewById<android.widget.TextView>(R.id.homeExpiry)
        val expires = Prefs(this).expiresAt
        if (expires <= 0L) {
            label.visibility = View.GONE
            return
        }
        val clock = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
        label.text = getString(R.string.home_expires, clock.format(java.util.Date(expires)))
        label.visibility = View.VISIBLE
    }

    private fun drawWeather(now: Weather.Now) {
        val icon = findViewById<android.widget.TextView>(R.id.homeWeatherIcon)
        val degrees = findViewById<android.widget.TextView>(R.id.homeWeatherNow)
        val town = findViewById<android.widget.TextView>(R.id.homeWeatherTown)
        icon.text = now.icon
        degrees.text = getString(R.string.weather_degrees, now.degrees)
        town.text = now.town
        icon.visibility = View.VISIBLE
        degrees.visibility = View.VISIBLE
        town.visibility = if (now.town.isBlank()) View.GONE else View.VISIBLE
    }

    /**
     * Checks whether the phone has asked for a channel.
     *
     * Only while the home screen is in front, which is exactly when the
     * television is idle and has nothing to lose by switching. A few hundred
     * bytes every five seconds - less than one second of video.
     */
    private val castWatch = object : Runnable {
        override fun run() {
            lifecycleScope.launch {
                val command = withContext(Dispatchers.IO) { CastLink.peek(prefs) }
                if (command != null && !isFinishing) obeyCast(command)
            }
            handler.postDelayed(this, 5_000L)
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 20_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        if (!prefs.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_home)
        showBackdrop()
        showWeather()
        showExpiry()
        clock = findViewById(R.id.homeClock)
        today = findViewById(R.id.homeDate)

        if (!Catalog.isLoaded) Catalog.load(this)

        findViewById<View>(R.id.tileLive).setOnClickListener { open(Kind.LIVE) }
        findViewById<View>(R.id.tileMovies).setOnClickListener { open(Kind.VOD) }
        findViewById<View>(R.id.tileSeries).setOnClickListener { open(Kind.SERIES) }

        findViewById<View>(R.id.homeSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.homeSearch).setOnClickListener {
            // One box across everything - channels, films and series together.
            startActivity(
                Intent(this, BrowseActivity::class.java)
                    .putExtra(BrowseActivity.EXTRA_SEARCH_ALL, true)
            )
        }
        findViewById<View>(R.id.homeRecordings).setOnClickListener {
            RecordingsActivity.open(this)
        }
        findViewById<View>(R.id.homeEpg).setOnClickListener {
            startActivity(Intent(this, EpgActivity::class.java))
        }
        findViewById<View>(R.id.homeRefresh).setOnClickListener {
            startActivity(
                Intent(this, SyncActivity::class.java).putExtra(SyncActivity.EXTRA_FORCE, true)
            )
            finish()
        }
        findViewById<View>(R.id.featuredPanel).setOnClickListener { openFeatured() }

        loadArtwork()
        findViewById<View>(R.id.tileLive).requestFocus()
    }

    override fun onStart() {
        super.onStart()
        updateClock()
        handler.post(tick)
        handler.post(castWatch)
        showMessage()
        checkExpiry()
        runCatching { findViewById<android.webkit.WebView>(R.id.homeBackdrop).onResume() }
    }

    /** Puts on whatever the phone asked for, if this television knows the channel. */
    private fun obeyCast(command: CastLink.Command) {
        val channel = Catalog.live.firstOrNull { it.streamId == command.id }
        // Acknowledged either way: a channel this box cannot find would otherwise
        // sit in the letterbox being retried every five seconds.
        lifecycleScope.launch { withContext(Dispatchers.IO) { CastLink.ack(prefs) } }
        if (channel == null) return
        Catalog.playbackQueue = Catalog.live
        PlayerActivity.start(
            this,
            urls = prefs.client().liveUrls(channel.streamId),
            title = channel.name,
            kind = Kind.LIVE,
            contentId = channel.streamId
        )
    }

    // ---------- a message from JohnnyTV ----------

    /**
     * Whatever is written in config.json, shown until it is read.
     *
     * The text itself is the record of what has been read, so writing a new
     * message is enough to make it appear again - there is no number to
     * remember to change, and clearing the field takes it off every screen.
     *
     * The cached message goes up straight away, then config.json is fetched
     * again in the background. Without that second look a message would only
     * ever arrive on a cold start, and a box on a shelf that nobody switches
     * off would never see one at all.
     */
    private fun showMessage() {
        drawMessage()
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching { RemoteConfigLoader.fetch(Config.CONFIG_URL) }.getOrNull()
            } ?: return@launch
            if (isFinishing || isDestroyed) return@launch
            val notice = fresh.notice.trim()
            if (notice == prefs.message.trim()) return@launch
            prefs.message = notice
            drawMessage()
        }
    }

    /**
     * Splits a message into the bit that shouts and the bit that explains.
     *
     * Written as one line in config.json, so the break has to come from the
     * punctuation somebody would type anyway: a line break, or a dash with
     * spaces around it. "Watch JohnnyTV anywhere for $5 - phone, laptop, car"
     * becomes a headline and a quieter second line. No dash, no second line,
     * and the whole thing is simply the headline.
     */
    private fun splitMessage(message: String): Pair<String, String> {
        val breaks = listOf("\n", " — ", " – ", " - ")
        for (mark in breaks) {
            val at = message.indexOf(mark)
            if (at > 0) {
                val head = message.substring(0, at).trim()
                val rest = message.substring(at + mark.length).trim()
                if (head.isNotEmpty() && rest.isNotEmpty()) return head to rest
            }
        }
        return message to ""
    }

    private fun drawMessage() {
        val band = findViewById<View>(R.id.homeMessage)
        val text = findViewById<TextView>(R.id.homeMessageText)
        val detail = findViewById<TextView>(R.id.homeMessageDetail)
        val dismiss = findViewById<TextView>(R.id.homeMessageDismiss)

        val message = prefs.message.trim()
        if (message.isEmpty() || message == prefs.messageRead) {
            val hadFocus = band.hasFocus()
            band.visibility = View.GONE
            if (hadFocus) findViewById<View>(R.id.tileLive).requestFocus()
            return
        }

        val (headline, rest) = splitMessage(message)
        text.text = headline
        detail.text = rest
        detail.visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
        band.visibility = View.VISIBLE
        dismiss.setOnClickListener {
            prefs.messageRead = message
            band.visibility = View.GONE
            findViewById<View>(R.id.tileLive).requestFocus()
        }
        // The band is put under the remote rather than left to be found. A
        // full-width strip of text sitting above a row of tiles is easy to
        // skip past, and a message nobody can dismiss is one that comes back
        // for ever.
        dismiss.requestFocus()
    }

    // ---------- subscription reminder ----------

    /**
     * Warns a customer whose line is nearly up.
     *
     * The date comes from the portal's own answer at sign-in, so it is right for
     * every customer on either service with no list to keep by hand - and no
     * customer names sitting in a public config file. The last known date is kept
     * on the device, so the warning still appears on a day the portal is slow.
     *
     * Shown once per launch from a fortnight out, and every time Home appears in
     * the last three days, when it stops being information and starts being
     * urgent.
     */
    private fun checkExpiry() {
        lifecycleScope.launch {
            val known = prefs.expiresAt
            val fresh = withContext(Dispatchers.IO) {
                runCatching { prefs.client().accountExpiry() }.getOrDefault(0L)
            }
            if (fresh > 0L) prefs.expiresAt = fresh
            val expiry = if (fresh > 0L) fresh else known
            if (expiry <= 0L || isFinishing) return@launch

            val left = expiry - System.currentTimeMillis()
            val days = TimeUnit.MILLISECONDS.toDays(left).toInt()
            if (left <= 0L || days > REMIND_WITHIN_DAYS) return@launch

            val today = System.currentTimeMillis() / TimeUnit.DAYS.toMillis(1)
            val urgent = days <= URGENT_DAYS

            // This runs whenever Home comes back to the front, which includes
            // backing out of a channel - so without a guard the same notice went
            // up again and again. That is nagging rather than reminding, and a
            // warning people learn to dismiss has stopped working. From a
            // fortnight out it appears once per launch; inside the last few days
            // it returns every time, because by then missing it costs somebody
            // their television.
            if (!urgent) {
                if (remindedThisLaunch || prefs.expiryShownOn == today) return@launch
            }
            remindedThisLaunch = true
            prefs.expiryShownOn = today
            showExpiryDialog(days)
        }
    }

    private fun showExpiryDialog(days: Int) {
        val headline = when {
            days <= 0 -> getString(R.string.expiry_today)
            days == 1 -> getString(R.string.expiry_tomorrow)
            else -> getString(R.string.expiry_days, days)
        }
        AlertDialog.Builder(this)
            .setTitle(headline)
            .setPositiveButton(R.string.expiry_ok, null)
            .show()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(castWatch)
        // Nothing animates while somebody is watching television.
        runCatching { findViewById<android.webkit.WebView>(R.id.homeBackdrop).onPause() }
    }

    // ---------- artwork ----------

    private fun loadArtwork() {
        // Three different pieces of artwork, so the tiles don't all show the same poster.
        val recentMovies = Catalog.vod.filter { it.icon.isNotBlank() }
            .sortedByDescending { it.added }
            .take(40)
        setArt(findViewById(R.id.moviesArt), recentMovies.getOrNull(0)?.icon)
        setArt(findViewById(R.id.liveArt), recentMovies.randomOrNull()?.icon)

        val series = Catalog.series.filter { it.cover.isNotBlank() }
            .sortedByDescending { it.added }
            .firstOrNull()
        setArt(findViewById(R.id.seriesArt), series?.cover)

        loadFeatured()
    }

    private fun setArt(view: ImageView, url: String?) {
        view.load(if (url.isNullOrBlank()) null else url) {
            crossfade(true)
            placeholder(R.drawable.tile_placeholder)
            error(R.drawable.tile_placeholder)
            fallback(R.drawable.tile_placeholder)
        }
    }

    /** A recent series with a wide backdrop, so the panel looks like a poster not a thumbnail. */
    private fun loadFeatured() {
        val recent = Catalog.series.sortedByDescending { it.added }.take(60)
        val choice = recent.filter { it.backdrop.isNotBlank() }.randomOrNull()
            ?: recent.filter { it.cover.isNotBlank() }.randomOrNull()
            ?: Catalog.series.firstOrNull { it.cover.isNotBlank() }

        featured = choice
        val art = findViewById<ImageView>(R.id.featuredArt)
        val title = findViewById<TextView>(R.id.featuredTitle)
        val meta = findViewById<TextView>(R.id.featuredMeta)

        if (choice == null) {
            art.setImageResource(R.drawable.tile_placeholder)
            title.text = getString(R.string.app_name)
            meta.text = ""
            return
        }

        setArt(art, choice.backdrop.ifBlank { choice.cover })
        title.text = choice.name
        meta.text = choice.metaLine()
    }

    // ---------- navigation ----------

    private fun open(kind: Kind, startCategory: String? = null, focusSearch: Boolean = false) {
        val intent = Intent(this, BrowseActivity::class.java)
            .putExtra(BrowseActivity.EXTRA_KIND, kind.name)
        if (startCategory != null) intent.putExtra(BrowseActivity.EXTRA_START_CATEGORY, startCategory)
        if (focusSearch) intent.putExtra(BrowseActivity.EXTRA_FOCUS_SEARCH, true)
        startActivity(intent)
    }

    private fun openFeatured() {
        val choice = featured ?: return
        startActivity(
            Intent(this, SeriesActivity::class.java)
                .putExtra(SeriesActivity.EXTRA_SERIES_ID, choice.seriesId)
                .putExtra(SeriesActivity.EXTRA_TITLE, choice.name)
                .putExtra(SeriesActivity.EXTRA_COVER, choice.cover)
        )
    }

    private fun updateClock() {
        val now = Date()
        clock.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
        today.text = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(now)
    }

    private companion object {
        /** How many days out the reminder starts appearing. */
        const val REMIND_WITHIN_DAYS = 14
        /** Inside this it returns every time Home does, rather than once a launch. */
        const val URGENT_DAYS = 3

        /**
         * Whether this run of the app has already given the reminder. Lives on
         * the companion so it lasts as long as the process does - which is what
         * "once when they open it" actually means on a box that is never closed.
         */
        @Volatile
        var remindedThisLaunch = false
    }
}
