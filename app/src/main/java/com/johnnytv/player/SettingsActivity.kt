package com.johnnytv.player

import android.content.Intent
import android.os.Bundle
import android.view.View
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

        findViewById<View>(R.id.refreshRow).setOnClickListener { refreshCatalogue() }

        val previewState = findViewById<TextView>(R.id.previewState)
        showPreviewState(previewState)
        findViewById<View>(R.id.previewRow).setOnClickListener {
            prefs.previewEnabled = !prefs.previewEnabled
            showPreviewState(previewState)
        }
        findViewById<View>(R.id.clearSearchRow).setOnClickListener {
            prefs.clearRecentSearches()
            Toast.makeText(this, R.string.searches_cleared, Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.signOutRow).setOnClickListener { confirmSignOut() }

        val errorRow = findViewById<View>(R.id.errorRow)
        errorRow.visibility = if (CrashReporter.lastCrash(this) == null) View.GONE else View.VISIBLE
        errorRow.setOnClickListener { showLastError() }

        versionLabel.text = getString(R.string.version_label, versionName(), versionCode())
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
