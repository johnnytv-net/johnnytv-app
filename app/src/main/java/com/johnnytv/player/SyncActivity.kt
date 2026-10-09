package com.johnnytv.player

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/**
 * Pulls the catalogue down once so browsing is instant afterwards.
 * Skipped entirely when a recent cache is already on the device.
 */
class SyncActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var message: TextView
    private lateinit var spinner: SpinnerView

    /** Everything that makes up one section's row, kept together. */
    private class Row(
        val group: View,
        val count: TextView,
        val tick: ImageView,
        val bar: ProgressBar
    )

    private lateinit var live: Row
    private lateinit var vod: Row
    private lateinit var series: Row

    /** Set once the sync has given up, when any press means "let me sign in again". */
    private var stuck = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        if (!prefs.isLoggedIn) {
            goTo(LoginActivity::class.java)
            return
        }

        setContentView(R.layout.activity_sync)
        message = findViewById(R.id.syncMessage)
        spinner = findViewById(R.id.syncSpinner)
        message.setOnClickListener { if (stuck) signInAgain() }

        live = Row(findViewById(R.id.rowLive), findViewById(R.id.countLive), findViewById(R.id.tickLive), findViewById(R.id.barLive))
        vod = Row(findViewById(R.id.rowVod), findViewById(R.id.countVod), findViewById(R.id.tickVod), findViewById(R.id.barVod))
        series = Row(findViewById(R.id.rowSeries), findViewById(R.id.countSeries), findViewById(R.id.tickSeries), findViewById(R.id.barSeries))

        waiting(live)
        waiting(vod)
        waiting(series)

        val forced = intent.getBooleanExtra(EXTRA_FORCE, false)
        start(forced)
    }

    private fun start(forced: Boolean) {
        lifecycleScope.launch {
            /*
             * A cached list is a day old at most, which is fine for channels and
             * wrong for the rules that arrange them. The list is only arranged
             * while it is being fetched, so a rule written this afternoon would
             * otherwise sit unused until tomorrow, behind a screen still showing
             * yesterday's arrangement and no way to tell why. New rules are
             * therefore a reason to fetch, cache or no cache.
             */
            if (!forced && !Lineups.rulesChanged) {
                val cached = withContext(Dispatchers.IO) { Catalog.load(this@SyncActivity) }
                val age = System.currentTimeMillis() - prefs.lastSync
                if (cached && age < CACHE_MAX_AGE_MS) {
                    goTo(HomeActivity::class.java)
                    return@launch
                }
            }

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    Catalog.sync(this@SyncActivity, prefs.client()) { section, status ->
                        runOnUiThread { onProgress(section, status) }
                    }
                }
            }

            spinner.visibility = View.GONE

            result.onSuccess {
                // Whatever the rules said has now been applied to the list.
                Lineups.changeHandled()
                if (Catalog.isLoaded) {
                    goTo(HomeActivity::class.java)
                } else {
                    fail(getString(R.string.sync_empty) + whatWentWrong())
                }
            }.onFailure { error ->
                // A cached copy is better than a dead end.
                val cached = withContext(Dispatchers.IO) { Catalog.load(this@SyncActivity) }
                if (cached) {
                    goTo(HomeActivity::class.java)
                } else {
                    fail((error.message ?: getString(R.string.sync_failed)) + whatWentWrong())
                }
            }
        }
    }

    private fun onProgress(section: String, status: String) {
        val row = when (section) {
            Catalog.SECTION_LIVE -> live
            Catalog.SECTION_VOD -> vod
            Catalog.SECTION_SERIES -> series
            else -> return
        }
        when (status) {
            Catalog.STATUS_WORKING -> working(row)
            Catalog.STATUS_DONE -> done(row, countFor(section))
            Catalog.STATUS_EMPTY -> done(row, "")
            else -> waiting(row)
        }
    }

    /** Only sections that have finished can be counted, which is when they are shown. */
    private fun countFor(section: String): String {
        val number = NumberFormat.getIntegerInstance()
        // What the section itself reported on the way past, falling back to the
        // catalogue for a sync that is already finished and loaded.
        val found = Catalog.counted[section] ?: when (section) {
            Catalog.SECTION_LIVE -> Catalog.live.size
            Catalog.SECTION_VOD -> Catalog.vod.size
            Catalog.SECTION_SERIES -> Catalog.series.size
            else -> 0
        }
        return when (section) {
            Catalog.SECTION_LIVE -> getString(R.string.count_channels, number.format(found))
            Catalog.SECTION_VOD -> getString(R.string.count_films, number.format(found))
            Catalog.SECTION_SERIES -> getString(R.string.count_series, number.format(found))
            else -> ""
        }
    }

    // ---------- the three states of a row ----------

    private fun waiting(row: Row) {
        row.group.alpha = DIMMED
        row.bar.isIndeterminate = false
        row.bar.progress = 0
        row.tick.visibility = View.INVISIBLE
        row.count.text = ""
    }

    private fun working(row: Row) {
        row.group.alpha = 1f
        // Nothing reports a percentage on the way, so the bar travels instead of
        // pretending to know how far through it is.
        row.bar.isIndeterminate = true
        row.tick.visibility = View.INVISIBLE
        row.count.text = ""
    }

    private fun done(row: Row, count: String) {
        row.group.alpha = 1f
        row.bar.isIndeterminate = false
        row.bar.progress = 100
        row.tick.visibility = View.VISIBLE
        row.count.text = count
    }

    /*
     * A sync that ends with nothing used to be a locked room: the screen said
     * "check your account" and offered no way to. A login restored by the
     * device after a reinstall, pointing at a server that has since moved,
     * sat there for good. So the dead end now says what each section ran
     * into, and any press - a tap on the message, Back, OK - drops the saved
     * login and goes back to the sign-in screen.
     */
    private fun fail(text: String) {
        stuck = true
        message.text = text + "\n\n" + getString(R.string.sync_sign_in_again)
        message.visibility = View.VISIBLE
    }

    /** What the sections that came back empty actually said, one line each. */
    private fun whatWentWrong(): String {
        val reasons = Catalog.reasons
        if (reasons.isEmpty()) return ""
        return "\n" + reasons.entries.joinToString("\n") { (section, why) ->
            section.lowercase().replaceFirstChar { it.uppercase() } + ": " + why
        }
    }

    private fun signInAgain() {
        prefs.clearCredentials()
        goTo(LoginActivity::class.java)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (stuck && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            signInAgain()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun goTo(target: Class<*>) {
        startActivity(Intent(this, target))
        finish()
    }

    companion object {
        const val EXTRA_FORCE = "force_sync"
        private const val CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val DIMMED = 0.38f
    }
}
