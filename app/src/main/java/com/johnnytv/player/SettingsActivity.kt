package com.johnnytv.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var accountLabel: TextView
    private lateinit var catalogueLabel: TextView
    private lateinit var versionLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_settings)

        accountLabel = findViewById(R.id.accountSummary)
        catalogueLabel = findViewById(R.id.catalogueSummary)
        versionLabel = findViewById(R.id.versionLabel)
        showPanel(findViewById(R.id.panelLabel))

        findViewById<View>(R.id.refreshRow).setOnClickListener { refreshCatalogue() }

        val previewState = findViewById<TextView>(R.id.previewState)
        showPreviewState(previewState)
        findViewById<View>(R.id.previewRow).setOnClickListener {
            prefs.previewEnabled = !prefs.previewEnabled
            showPreviewState(previewState)
        }
        val storageState = findViewById<TextView>(R.id.storageState)
        showStorageState(storageState)
        findViewById<View>(R.id.storageRow).setOnClickListener { chooseStorage(storageState) }
        findViewById<View>(R.id.checkRow).setOnClickListener { showDeviceCheck() }
        findViewById<View>(R.id.phoneRow).setOnClickListener { checkPhoneRecordings() }

        findViewById<View>(R.id.clearFavouritesRow).setOnClickListener { clearFavourites() }
        findViewById<View>(R.id.playbackLogRow).setOnClickListener { showPlaybackLog() }
        findViewById<View>(R.id.guideCheckRow).setOnClickListener { checkTheGuide() }

        val awayState = findViewById<TextView>(R.id.awayState)
        showAwayState(awayState)
        findViewById<View>(R.id.awayRow).setOnClickListener { askForAwayBox(awayState) }

        val shareState = findViewById<TextView>(R.id.shareState)
        shareState.setText(if (prefs.shareRecordings) R.string.share_on else R.string.share_off)
        findViewById<View>(R.id.shareRow).setOnClickListener {
            prefs.shareRecordings = !prefs.shareRecordings
            ShareService.apply(this)
            shareState.setText(if (prefs.shareRecordings) R.string.share_on else R.string.share_off)
        }

        val weatherState = findViewById<TextView>(R.id.weatherState)
        showWeatherState(weatherState)
        findViewById<View>(R.id.weatherRow).setOnClickListener { askForTown(weatherState) }

        findViewById<View>(R.id.clearSearchRow).setOnClickListener {
            prefs.clearRecentSearches()
            Toast.makeText(this, R.string.searches_cleared, Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.signOutRow).setOnClickListener { confirmSignOut() }

        val errorRow = findViewById<View>(R.id.errorRow)
        errorRow.visibility = if (CrashReporter.lastCrash(this) == null) View.GONE else View.VISIBLE
        errorRow.setOnClickListener { showLastError() }

        // The version, and which service the box is on, on one line.
        val code = serviceCode()
        versionLabel.text = getString(R.string.version_label, versionName(), versionCode()) +
            if (code.isBlank()) "" else "  ·  " + code
        showCatalogueSummary()
        loadAccount()
    }

    /**
     * A preview holds one of the line's connections while it runs, so this is
     * worth being able to switch off rather than being a hidden behaviour.
     */
    private fun showPreviewState(label: TextView) {
        label.setText(
            if (prefs.previewEnabled) R.string.channel_preview_on else R.string.channel_preview_off
        )
    }

    /**
     * Which drive recordings are written to.
     *
     * Only worth a question when there is more than one place to put them; with
     * a single drive plugged in the row simply says where they are going, and a
     * press still opens the list so somebody can see there is no choice to make
     * rather than wondering whether they missed one.
     */
    private fun showStorageState(label: TextView) {
        val target = Storage.chosen(this)
        label.text = if (target == null) {
            getString(R.string.record_no_storage)
        } else {
            getString(
                R.string.recordings_storage,
                target.label,
                String.format(
                    Locale.getDefault(),
                    "%.0f GB",
                    target.freeBytes / (1024.0 * 1024.0 * 1024.0)
                )
            )
        }
    }

    private fun chooseStorage(label: TextView) {
        val targets = Storage.targets(this)
        if (targets.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.recording_storage_row)
                .setMessage(R.string.record_no_storage)
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        val names = targets.map { target ->
            getString(
                R.string.recordings_storage,
                target.label,
                String.format(
                    Locale.getDefault(),
                    "%.0f GB",
                    target.freeBytes / (1024.0 * 1024.0 * 1024.0)
                )
            )
        }.toTypedArray()

        showOptions(getString(R.string.recording_storage_row), names.toList()) { which ->
            prefs.recordingVolume = targets[which].id
            showStorageState(label)
        }
    }

    /**
     * Looking in the letterbox, in front of somebody.
     *
     * The five-minute round is deliberately silent, which is right until
     * nothing is arriving and nobody can say why. This does the same work and
     * shows its workings: who it thinks it is, whether the letterbox let it in,
     * how many recordings were waiting, and whether the list grew.
     */
    private fun checkPhoneRecordings() {
        val waiting = AlertDialog.Builder(this)
            .setTitle(R.string.phone_recordings)
            .setMessage(R.string.phone_recordings_looking)
            .create()
        waiting.show()
        kotlin.concurrent.thread {
            val text = runCatching { Postman.collectAndDescribe(this) }
                .getOrElse { "Failed: " + (it.message ?: it.javaClass.simpleName) }
            runOnUiThread {
                waiting.dismiss()
                AlertDialog.Builder(this)
                    .setTitle(R.string.phone_recordings)
                    .setMessage(text)
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }
    }

    private fun showDeviceCheck() {
        AlertDialog.Builder(this)
            .setTitle(R.string.check_device)
            .setMessage(DeviceCheck.report(this))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    /**
     * WATCHING FROM SOMEWHERE ELSE.
     *
     * At home the boxes find each other by announcing themselves. Away from
     * home nothing is announced and nothing is heard, so the box holding the
     * recordings has to be named - by its Tailscale address, which stays the
     * same wherever either end is. Typed once, and after that a phone on
     * mobile data lists and plays the Shield's recordings exactly as the
     * television upstairs does.
     */
    /**
     * The way out of a favourites list that has gone wrong.
     *
     * Removing them one at a time is the normal way; this is for the case
     * where somebody has a list full of channels their portal no longer
     * carries, or simply wants to start again.
     */
    private fun clearFavourites() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_favourites)
            .setMessage(R.string.clear_favourites_confirm)
            .setPositiveButton(R.string.clear_favourites_yes) { _, _ ->
                prefs.clearFavourites()
                // Show what is left rather than claiming it worked: if anything
                // survives this, the next screen says so in plain numbers.
                AlertDialog.Builder(this)
                    .setTitle(R.string.clear_favourites)
                    .setMessage(prefs.favouritesReport())
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
            .setNeutralButton(R.string.clear_favourites_show) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(R.string.clear_favourites)
                    .setMessage(prefs.favouritesReport())
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * The last recording's playback, step by step.
     *
     * Only useful when something has gone wrong, and then it is the only thing
     * that is: it survives the app being killed, so the last line written is
     * whatever happened immediately before it disappeared.
     */
    private fun showPlaybackLog() {
        val text = PlaybackLog.read(this) ?: getString(R.string.playback_log_empty)
        AlertDialog.Builder(this)
            .setTitle(R.string.playback_log_row)
            .setMessage(text)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.clear_favourites_yes) { _, _ -> PlaybackLog.clear(this) }
            .show()
    }

    /**
     * IS THE GUIDE EMPTY, OR IS THE APP NOT ASKING?
     *
     * One panel shows programmes and another shows nothing, and from the sofa
     * those look identical - which leaves somebody wondering whether to raise
     * it with their supplier or with whoever wrote the app.
     *
     * So the guide is asked for, here and now, on a handful of channels, and
     * what comes back is reported plainly: how many listings, for which
     * channels, from which service. Empty answers across the board are the
     * panel's to explain; listings here and nothing on screen would be mine.
     *
     * It also clears what was remembered, so the next look at the guide
     * fetches afresh - which is the "update EPG" that other players offer.
     */
    private fun checkTheGuide() {
        val working = AlertDialog.Builder(this)
            .setTitle(R.string.guide_check_row)
            .setMessage(R.string.guide_check_working)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching { askForTheGuide() }.getOrElse { "Could not ask: " + it.message }
            }
            runCatching { working.dismiss() }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.guide_check_row)
                .setMessage(report)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private fun askForTheGuide(): String {
        EpgCache.clear()
        val client = prefs.client() ?: return "Not signed in."
        val channels = Catalog.live.take(5)
        if (channels.isEmpty()) return "No channels loaded yet - refresh the catalogue first."

        val out = StringBuilder()
        var total = 0
        for (channel in channels) {
            val listings = runCatching { client.epg(channel.streamId) }.getOrDefault(emptyList())
            total += listings.size
            out.append(channel.name.take(28)).append(": ")
                .append(if (listings.isEmpty()) "nothing" else listings.size.toString() + " listings")
                .append('\n')
        }

        out.append('\n')
        out.append(
            if (total == 0) {
                "This service is sending no guide data at all. That is theirs to fix - " +
                    "ask them to check the EPG for your account."
            } else {
                "Guide data is arriving. What was remembered has been cleared, so the " +
                    "guide will fetch afresh next time it is opened."
            }
        )
        return out.toString()
    }

    private fun showAwayState(label: TextView) {
        val typed = prefs.awayBox
        label.text = if (typed.isBlank()) getString(R.string.away_off) else typed
    }

    private fun askForAwayBox(label: TextView) {
        val input = EditText(this).apply {
            setText(prefs.awayBox)
            hint = "100.119.61.127"
            setSingleLine()
            setTextColor(getColor(R.color.text_primary))
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.away_row)
            .setMessage(R.string.away_help)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.awayBox = input.text.toString()
                showAwayState(label)
            }
            .setNeutralButton(R.string.away_forget) { _, _ ->
                prefs.awayBox = ""
                showAwayState(label)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * WHICH SERVICE THIS BOX IS ON - WITHOUT NAMING IT.
     *
     * The app carries three services and tries each in turn at sign-in, so
     * nothing on screen has ever said which one answered. That costs time
     * whenever a customer rings up and somebody has to guess which panel to
     * look in.
     *
     * But the address is the one thing the branding exists to hide: a customer
     * reading "edge.sb" on their own television knows exactly who supplies it,
     * and can go straight there. So it is shown as a short code - DN, SB, JTV -
     * which says which panel to open without saying who runs it.
     */
    /**
     * Which service this box is signed in to, as a short code.
     *
     * Shown beside the version as well as on the panel row, because the one
     * question asked down a telephone is "which panel is it on" and the
     * answer should be on screen without anybody hunting for it.
     */
    private fun serviceCode(): String {
        val server = prefs.server
        if (server.isBlank()) return ""
        val host = server
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .lowercase()
        val known = Config.SERVERS.map { entry ->
            RemoteConfigLoader.resolve(entry)
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .lowercase()
        }
        val at = known.indexOfFirst { it == host }
        /*
         * Dino answers on several names - its own, and the ones bought for
         * it - so every door into the same panel has to read DN. A new
         * address for it belongs on this line.
         */
        return when {
            host.contains("dino") ||
                host.contains("bq-lines") ||
                host.contains("joy8k") -> "DN"
            host.contains("edge.sb") -> "SB"
            host.contains("edge.bz") -> "JTV"
            at >= 0 -> ('A' + at).toString()
            // Genuinely somewhere nobody here has heard of: support mode, or
            // a panel added since this build.
            else -> "··"
        }
    }

    private fun showPanel(label: TextView) {
        val server = prefs.server
        if (server.isBlank()) {
            label.text = ""
            return
        }
        val code = serviceCode()

        val name = prefs.friendlyName
        val base = if (name.isNotBlank()) {
            getString(R.string.panel_label_named, code, name)
        } else {
            getString(R.string.panel_label, code)
        }

        /*
         * WHETHER THE ARRANGEMENT FOUND THIS SERVICE.
         *
         * A lineup that matched nothing and a lineup that had nothing to do
         * look identical on a television: the same long list of folders, and no
         * way to tell a rule that is wrong from a rule that never ran at all.
         * An afternoon went on exactly that. So the line says which it was -
         * "list 43" when rules were found for this service, "list 131 raw"
         * when there were none.
         */
        val folders = Catalog.liveCategories.size
        val tuned = if (Lineups.forServer(server) != null) {
            " - list " + folders
        } else {
            " - list " + folders + " raw"
        }
        label.text = base + tuned
    }

    private fun showWeatherState(label: TextView) {
        val town = prefs.weatherTown
        label.text = if (town.isBlank()) getString(R.string.weather_town_automatic) else town
    }

    /**
     * Putting the weather's town right.
     *
     * Only ever needed by somebody the address lookup placed wrongly - a VPN,
     * usually - so it is a quiet row rather than a question anybody is asked.
     * Emptying it hands the job back to the lookup.
     */
    private fun askForTown(label: TextView) {
        val input = android.widget.EditText(this)
        input.setHint(R.string.weather_town_hint)
        input.setSingleLine(true)
        input.setText(prefs.weatherTown)

        AlertDialog.Builder(this)
            .setTitle(R.string.weather_town_row)
            .setMessage(R.string.weather_town_prompt)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val typed = input.text.toString().trim()
                if (typed.isBlank()) {
                    prefs.weatherTown = ""
                    prefs.weatherLatitude = 0.0
                    prefs.weatherLongitude = 0.0
                    Weather.forget()
                    showWeatherState(label)
                    return@setPositiveButton
                }
                kotlin.concurrent.thread {
                    val found = runCatching { Weather.findTown(typed) }.getOrNull()
                    runOnUiThread {
                        if (found == null) {
                            AlertDialog.Builder(this)
                                .setTitle(R.string.weather_town_row)
                                .setMessage(R.string.weather_town_not_found)
                                .setPositiveButton(R.string.close, null)
                                .show()
                        } else {
                            prefs.weatherTown = found.first
                            prefs.weatherLatitude = found.second
                            prefs.weatherLongitude = found.third
                            Weather.forget()
                            showWeatherState(label)
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCatalogueSummary() {
        val synced = prefs.lastSync
        val when_ = if (synced > 0) {
            SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(synced))
        } else {
            "-"
        }
        catalogueLabel.text = getString(
            R.string.catalogue_summary,
            Catalog.live.size, Catalog.vod.size, Catalog.series.size, when_
        )
    }

    private fun loadAccount() {
        accountLabel.text = getString(R.string.loading)
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                runCatching { prefs.client().accountSummary() }.getOrDefault(emptyMap())
            }
            accountLabel.text = if (summary.isEmpty()) {
                getString(R.string.account_unavailable)
            } else {
                summary.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            }
        }
    }

    private fun refreshCatalogue() {
        startActivity(
            Intent(this, SyncActivity::class.java).putExtra(SyncActivity.EXTRA_FORCE, true)
        )
        finish()
    }

    /** Shows the last crash so it can be read or photographed without a computer. */
    private fun showLastError() {
        val report = CrashReporter.lastCrash(this) ?: getString(R.string.no_errors)
        val body = TextView(this)
        body.text = report
        body.textSize = 11f
        body.setTextColor(getColor(R.color.text_secondary))
        body.setPadding(dp(20), dp(12), dp(20), dp(12))
        body.setTextIsSelectable(true)

        val scroll = ScrollView(this)
        scroll.addView(body)

        AlertDialog.Builder(this)
            .setTitle(R.string.last_error)
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .setNegativeButton(R.string.clear_error) { _, _ ->
                CrashReporter.clear(this)
                findViewById<View>(R.id.errorRow).visibility = View.GONE
            }
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle(R.string.sign_out)
            .setMessage(R.string.sign_out_confirm)
            .setPositiveButton(R.string.sign_out) { _, _ ->
                prefs.clearCredentials()
                Catalog.clear(this)
                // The next portal keeps its guide times in its own timezone.
                XtreamClient.forgetPortalOffset()
                startActivity(
                    Intent(this, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }

    private fun versionCode(): Long = try {
        PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
    } catch (e: Exception) {
        0L
    }
}
