package com.johnnytv.player

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var logo: ImageView
    private lateinit var noticeLabel: TextView
    private lateinit var serverInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var signInButton: Button
    // The brand spinner, not a ProgressBar - only its visibility is ever touched.
    private lateinit var progress: View
    private lateinit var statusLabel: TextView
    private lateinit var savedLabel: TextView
    private lateinit var savedList: android.widget.LinearLayout

    /** The update being offered, held so it survives the trip out to Settings. */
    private var pendingUpdate: RemoteConfig? = null
    private var awaitingInstallPermission = false
    /** Whatever update dialog is currently on screen, so it is never stacked. */
    private var updateDialog: AlertDialog? = null

    /**
     * Every service this build can sign in to, in the order to try them.
     *
     * A customer types only a username and password; whichever service knows
     * them is the one they get, and it is remembered for next time.
     */
    private var resolvedServers: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        awaitingInstallPermission = savedInstanceState?.getBoolean(KEY_AWAITING_INSTALL) == true
        prefs = Prefs(this)
        setContentView(R.layout.activity_login)

        logo = findViewById(R.id.logo)
        noticeLabel = findViewById(R.id.noticeLabel)
        serverInput = findViewById(R.id.serverInput)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        signInButton = findViewById(R.id.signInButton)
        progress = findViewById(R.id.loginProgress)
        statusLabel = findViewById(R.id.loginStatus)
        savedLabel = findViewById(R.id.savedLabel)
        savedList = findViewById(R.id.savedLogins)

        signInButton.setOnClickListener { attemptSignIn() }
        findViewById<ImageView>(R.id.passwordReveal).setOnClickListener { togglePassword(it as ImageView) }

        // Hidden support escape hatch: long-press the logo to type a server by hand.
        logo.setOnLongClickListener {
            // Deliberately does NOT pre-fill the current address - showing it here
            // would hand the portal to anyone who long-pressed the logo.
            serverInput.visibility = View.VISIBLE
            serverInput.setText("")
            statusLabel.text = getString(R.string.support_mode)
            true
        }

        showSavedLogins()
        loadConfigThenContinue()
    }

    /**
     * The lines this box has signed in with before.
     *
     * Deliberately plain: the username on a row, press to sign in, hold to
     * forget. No password is ever drawn on screen - the point is only that
     * nobody has to remember one to get back into their own television.
     */
    private fun showSavedLogins() {
        val saved = prefs.savedLogins
        savedList.removeAllViews()
        val show = saved.isNotEmpty()
        savedLabel.visibility = if (show) View.VISIBLE else View.GONE
        savedList.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        for (one in saved) {
            val row = layoutInflater.inflate(R.layout.item_saved_login, savedList, false)
            row.findViewById<TextView>(R.id.savedName).text = one.username
            row.setOnClickListener {
                usernameInput.setText(one.username)
                passwordInput.setText(one.password)
                attemptSignIn()
            }
            row.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle(one.username)
                    .setMessage(R.string.forget_login_confirm)
                    .setPositiveButton(R.string.forget_login) { _, _ ->
                        prefs.forgetLogin(one.username)
                        showSavedLogins()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                true
            }
            savedList.addView(row)
        }
    }

    private fun loadConfigThenContinue() {
        setBusy(true)
        statusLabel.text = getString(R.string.connecting)

        lifecycleScope.launch {
            val remote = withContext(Dispatchers.IO) {
                val loaded = RemoteConfigLoader.fetch(Config.CONFIG_URL)
                // Refresh the channel-logo overrides on the same trip.
                runCatching { LogoPack.refresh(applicationContext, loaded) }
                loaded
            }

            resolvedServers = buildServerList(remote)

            // Kept on the device so the expiry reminder can be shown even when
            // config.json is unreachable later.
            if (remote != null && remote.renewalContact.isNotBlank()) {
                prefs.renewalContact = remote.renewalContact
            }

            // The notice belongs on the home screen, not here.
            //
            // Almost nobody sees this screen: a signed-in customer is carried
            // through it in under a second, and the ones who do see it are
            // mid-way through typing a password, which is the worst possible
            // moment to offer them something. So it is stored and shown on the
            // home screen instead, where it can be read and dismissed properly.
            noticeLabel.visibility = View.GONE
            if (remote != null) prefs.message = remote.notice

            if (remote != null && isUpdateAvailable(remote)) {
                setBusy(false)
                showUpdateDialog(remote)
                // Nothing else may run while the dialog is up. A signed-in
                // customer would otherwise be carried straight into the app by
                // the block below, which finishes this activity and takes the
                // dialog down with it - so the offer to update was only ever
                // visible to people who were not logged in yet.
                return@launch
            }

            continueAfterConfig()
        }
    }

    /** Signing in, once nothing is standing in front of it. */
    private fun continueAfterConfig() {
        if (isFinishing || isDestroyed) return
        setBusy(false)
        statusLabel.text = ""

        if (resolvedServers.isEmpty()) {
            // Nothing to connect to - let the user type one rather than dead-ending.
            serverInput.visibility = View.VISIBLE
            statusLabel.text = getString(R.string.no_server)
            return
        }

        // Remember the first one so the app still works if config.json is ever
        // unreachable; a successful sign-in replaces it with the one that worked.
        if (prefs.server.isBlank()) prefs.server = resolvedServers.first()

        when {
            Config.PRESET_USERNAME.isNotBlank() && Config.PRESET_PASSWORD.isNotBlank() -> {
                usernameInput.setText(Config.PRESET_USERNAME)
                passwordInput.setText(Config.PRESET_PASSWORD)
                attemptSignIn()
            }
            prefs.isLoggedIn -> goToApp()
        }
    }

    /**
     * The services to try, best first.
     *
     * A server typed into the hidden support screen always wins, so support can
     * fix a broken config. Otherwise config.json leads, the built-in list fills
     * in when it is unreachable, and whichever service last worked on this
     * device is moved to the front so a returning customer signs in first time.
     */
    private fun buildServerList(remote: RemoteConfig?): List<String> {
        val out = ArrayList<String>()
        fun add(raw: String) {
            // Compare in the same shape the client stores, so "edge.bz:8080/" and
            // "http://edge.bz:8080" are recognised as the one portal.
            val one = XtreamClient.normalizeServer(RemoteConfigLoader.resolve(raw.trim()))
            if (one.isNotBlank() && !out.contains(one)) out.add(one)
        }

        if (prefs.manualServer.isNotBlank()) {
            add(prefs.manualServer)
            return out
        }
        remote?.servers?.forEach { add(it) }
        Config.SERVERS.forEach { add(it) }
        if (Config.DEFAULT_SERVER.isNotBlank()) add(Config.DEFAULT_SERVER)
        if (prefs.server.isNotBlank()) add(prefs.server)

        val remembered = XtreamClient.normalizeServer(RemoteConfigLoader.resolve(prefs.server.trim()))
        val at = out.indexOf(remembered)
        if (at > 0) {
            out.removeAt(at)
            out.add(0, remembered)
        }
        return out
    }

    /**
     * Shows or hides what has been typed, so a long password can be checked
     * before signing in. The cursor is put back where it was, because changing
     * the input type sends it to the start.
     */
    private fun togglePassword(button: ImageView) {
        // The variation is a value, not a flag - visible-password still carries the
        // password bit, so it has to be compared against the mask, not tested.
        val hidden = (passwordInput.inputType and InputType.TYPE_MASK_VARIATION) ==
            InputType.TYPE_TEXT_VARIATION_PASSWORD
        val at = passwordInput.selectionEnd
        passwordInput.inputType = if (hidden) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        // Setting inputType switches the field to monospace, so put it back.
        passwordInput.typeface = Typeface.DEFAULT
        passwordInput.setSelection(at.coerceIn(0, passwordInput.text?.length ?: 0))
        button.setImageResource(if (hidden) R.drawable.ic_eye_off else R.drawable.ic_eye)
        button.contentDescription =
            getString(if (hidden) R.string.hide_password else R.string.show_password)
    }

    private fun attemptSignIn() {
        val typedServer = serverInput.text.toString().trim()
        val servers = if (serverInput.visibility == View.VISIBLE && typedServer.isNotBlank()) {
            listOf(RemoteConfigLoader.resolve(typedServer))
        } else {
            resolvedServers
        }
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString().trim()

        if (servers.isEmpty()) {
            statusLabel.text = getString(R.string.no_server)
            return
        }
        if (username.isBlank() || password.isBlank()) {
            statusLabel.text = getString(R.string.fill_all_fields)
            return
        }
        // Opening support mode and leaving the box empty clears a previous override,
        // so a device is never stuck on a portal that has since moved.
        if (serverInput.visibility == View.VISIBLE && typedServer.isBlank() && prefs.manualServer.isNotBlank()) {
            prefs.manualServer = ""
            resolvedServers = buildServerList(null)
        }

        setBusy(true)
        statusLabel.text = getString(R.string.signing_in)

        lifecycleScope.launch {
            var success: Pair<XtreamClient, String>? = null
            var refusal: String? = null
            var lastProblem: String? = null
            var sawRefusal = false

            /*
             * A name, rather than a code.
             *
             * Customers are given their own name and one shared password; the
             * site turns that pair into the real line. Asked first, and quietly:
             * if the site says nothing useful - or nothing at all - what was
             * typed is treated as real credentials and sign-in proceeds exactly
             * as before, so the codes still work and nobody set up the old way
             * is affected.
             */
            var user = username
            var pass = password
            var serverList = servers

            withContext(Dispatchers.IO) {
                val real = FriendlyLogin.lookUp(username, password)
                if (real != null) {
                    user = real.username
                    pass = real.password
                    if (real.server.isNotBlank()) {
                        // The site says which panel they are on, so that one is
                        // tried first; the usual list still follows as a fallback.
                        val named = RemoteConfigLoader.resolve(real.server)
                        serverList = listOf(named) + servers.filter { it != named }
                    }
                }
            }

            withContext(Dispatchers.IO) {
                for (address in serverList) {
                    val client = XtreamClient(address, user, pass)
                    try {
                        val status = client.login()
                        success = client to status
                        return@withContext
                    } catch (e: AccountInactive) {
                        // The line exists here, so this is the right service. Stop.
                        refusal = e.message
                        return@withContext
                    } catch (e: WrongCredentials) {
                        // Not this one - keep looking, but remember that a portal
                        // did answer, so an unreachable one does not steal the blame.
                        sawRefusal = true
                    } catch (e: Exception) {
                        // Unreachable or not a portal; remember it in case nothing works.
                        lastProblem = e.message
                    }
                }
            }

            setBusy(false)

            // Copied into plain values so they read as non-null below.
            val worked = success
            val refused = refusal
            val problem = lastProblem
            when {
                worked != null -> {
                    val client = worked.first
                    if (serverInput.visibility == View.VISIBLE && typedServer.isNotBlank()) {
                        prefs.manualServer = client.server
                    }
                    // The real line is stored, because that is what every other
                    // screen signs in with - and the friendly name beside it, so
                    // the site can be asked again at each start.
                    prefs.saveCredentials(client.server, user, pass)
                    prefs.friendlyName = if (user != username) username else ""
                    // Remembered for next time, so signing out costs nothing.
                    prefs.rememberLogin(client.server, user, pass)
                    statusLabel.text = worked.second
                    goToApp()
                }
                refused != null -> statusLabel.text = refused
                sawRefusal -> statusLabel.text = getString(R.string.wrong_login)
                else -> statusLabel.text = problem ?: getString(R.string.wrong_login)
            }
        }
    }

    private fun isUpdateAvailable(remote: RemoteConfig): Boolean {
        if (remote.latestVersionCode <= 0L || remote.downloadUrl.isBlank()) return false
        return remote.latestVersionCode > currentVersionCode()
    }

    private fun currentVersionCode(): Long = try {
        PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
    } catch (e: Exception) {
        0L
    }

    private fun showUpdateDialog(remote: RemoteConfig) {
        pendingUpdate = remote
        // A forced update leaves the form behind the dialog live, and the
        // installer and the browser both put that form back in reach. Taking the
        // button away is what actually makes it a gate.
        if (remote.forceUpdate) signInButton.isEnabled = false
        if (updateDialog?.isShowing == true) return

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.update_title)
            .setMessage(R.string.update_message)
            .setCancelable(!remote.forceUpdate)
            .setPositiveButton(R.string.update_now) { _, _ -> beginUpdate(remote) }
        if (!remote.forceUpdate) {
            // Declining has to hand the viewer back to signing in, because the
            // sign-in flow stopped to let this dialog be seen.
            builder.setNegativeButton(R.string.update_later) { _, _ ->
                pendingUpdate = null
                continueAfterConfig()
            }
            builder.setOnCancelListener {
                pendingUpdate = null
                continueAfterConfig()
            }
        }
        updateDialog = builder.show()
    }

    // ---------- installing the update ourselves ----------

    /**
     * The whole point of doing this in-app: a television box usually has no
     * browser, so handing it a download link left the customer stuck - and if
     * the update was forced, stuck with no way past the dialog at all.
     */
    private fun beginUpdate(remote: RemoteConfig) {
        pendingUpdate = remote
        if (!Updater.canInstall(this)) {
            askForInstallPermission(remote)
            return
        }

        val progressLabel = TextView(this).apply {
            setPadding(56, 40, 56, 16)
            textSize = 15f
            setText(R.string.update_downloading)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_title)
            .setView(progressLabel)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val file = Updater.download(applicationContext, remote.downloadUrl) { percent ->
                runOnUiThread {
                    progressLabel.text = getString(R.string.update_downloading) +
                        "  " + getString(R.string.update_percent, percent)
                }
            }
            // Dismissed first: the one path where the dialog is certainly still
            // attached to a dying activity is the path that must not skip it.
            runCatching { dialog.dismiss() }
            if (isFinishing || isDestroyed) return@launch

            if (file == null) {
                showUpdateFailed(remote)
                return@launch
            }
            if (!Updater.install(applicationContext, file)) {
                statusLabel.text = getString(R.string.update_no_installer)
                showUpdateFailed(remote)
            }
        }
    }

    /**
     * Android will not let an app install anything until the viewer says so,
     * once, per app. Sending them to that screen is the only way through.
     */
    private fun askForInstallPermission(remote: RemoteConfig) {
        AlertDialog.Builder(this)
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_message)
            .setCancelable(!remote.forceUpdate)
            .setPositiveButton(R.string.update_permission_go) { _, _ ->
                awaitingInstallPermission = true
                if (!Updater.requestInstallPermission(this)) {
                    awaitingInstallPermission = false
                    // Some cut-down television builds have no such screen at all.
                    if (!Updater.openInBrowser(this, remote.downloadUrl)) {
                        statusLabel.text = getString(R.string.update_no_installer)
                    }
                }
            }
            .apply {
                if (!remote.forceUpdate) {
                    setNegativeButton(R.string.update_later) { _, _ -> continueAfterConfig() }
                    setOnCancelListener { continueAfterConfig() }
                }
            }
            .show()
    }

    /**
     * Coming back from the Android setting that allows installs.
     *
     * Without this the viewer granted the permission, pressed Back, and found
     * nothing to press - the dialog telling them to "come back and press Update"
     * was gone, and on a forced update that was a dead end.
     */
    override fun onResume() {
        super.onResume()
        if (awaitingInstallPermission) {
            awaitingInstallPermission = false
            val remote = pendingUpdate ?: return
            if (Updater.canInstall(this)) beginUpdate(remote) else showUpdateDialog(remote)
            return
        }
        // Backing out of the package installer, or out of a browser, must not
        // leave a forced update behind. Put it straight back up.
        val forced = pendingUpdate?.takeIf { it.forceUpdate } ?: return
        if (updateDialog?.isShowing != true) showUpdateDialog(forced)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The box can kill this activity while the viewer is away in Settings.
        outState.putBoolean(KEY_AWAITING_INSTALL, awaitingInstallPermission)
    }

    /**
     * A failed download must never be a dead end. Even on a forced update there
     * is a way to try again, and the browser link stays available for boxes that
     * happen to have one.
     */
    private fun showUpdateFailed(remote: RemoteConfig) {
        AlertDialog.Builder(this)
            .setTitle(R.string.update_failed_title)
            .setMessage(R.string.update_failed_message)
            .setCancelable(!remote.forceUpdate)
            .setPositiveButton(R.string.update_retry) { _, _ -> beginUpdate(remote) }
            .setNeutralButton(R.string.update_open_link) { _, _ ->
                if (!Updater.openInBrowser(this, remote.downloadUrl)) {
                    statusLabel.text = getString(R.string.update_no_installer)
                }
            }
            .apply { if (!remote.forceUpdate) setNegativeButton(R.string.update_later, null) }
            .show()
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        signInButton.isEnabled = !busy
    }

    private fun goToApp() {
        startActivity(Intent(this, SyncActivity::class.java))
        finish()
    }

    private companion object {
        const val KEY_AWAITING_INSTALL = "awaiting_install_permission"
    }
}
