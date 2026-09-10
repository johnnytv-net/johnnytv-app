package com.johnnytv.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * FETCHING AND INSTALLING AN UPDATE, WITHOUT A BROWSER.
 *
 * The old path handed the download link to whatever app claimed http. On a
 * phone that is a browser; on a television box it is very often nothing at all,
 * and the customer was left looking at a dialog they could not get past with no
 * way to get the file. Somebody then had to drive over with a USB stick.
 *
 * So the app fetches its own APK and hands it to Android's package installer.
 * The only thing the viewer has to agree to is the install itself.
 */
object Updater {

    /** Where the downloaded APK lands. Cleared before each attempt. */
    private fun target(context: Context): File {
        val dir = File(context.cacheDir, "updates")
        dir.mkdirs()
        return File(dir, "JohnnyTV-update.apk")
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // A television box on a slow line still has to finish: generous on
            // the body, strict on the initial connection.
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .followRedirects(true)
            // GitHub answers the download URL with a redirect, so redirects have
            // to be followed - but never off TLS. Without this an https link that
            // redirects to http would be followed silently, which is the exact
            // substitution the https check below exists to prevent.
            .followSslRedirects(false)
            .build()
    }

    /**
     * Downloads the APK, reporting 0..100 as it goes.
     *
     * [onProgress] is called from a background thread; callers marshal it.
     * Returns the file, or null if anything at all went wrong - a failed update
     * is a thing to report calmly, not to crash over.
     */
    suspend fun download(
        context: Context,
        url: String,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        // The app allows cleartext for the portal's own streams, and follows
        // redirects. Neither is acceptable for something we are about to hand to
        // the package installer: over plain http anyone between the box and the
        // server could substitute the APK. An update arrives over TLS or not at
        // all.
        if (!url.startsWith("https://", ignoreCase = true)) return@withContext null

        val file = target(context)
        runCatching { file.delete() }

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", Config.USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                // Whatever the journey, the file itself arrived over TLS.
                if (!response.request.url.isHttps) return@withContext null
                val body = response.body ?: return@withContext null
                val total = body.contentLength()

                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReported = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                val percent = ((done * 100) / total).toInt().coerceIn(0, 100)
                                // Only on change, or a slow line spends its time
                                // posting to the main thread instead of reading.
                                if (percent != lastReported) {
                                    lastReported = percent
                                    onProgress(percent)
                                }
                            }
                        }
                        output.flush()
                    }
                }
            }
        } catch (e: CancellationException) {
            // A cancelled download is not a failed one; let it travel.
            runCatching { file.delete() }
            throw e
        } catch (e: Exception) {
            runCatching { file.delete() }
            return@withContext null
        }

        // A truncated download installs as a corrupt package, which is worse than
        // no download at all.
        if (file.length() < MIN_PLAUSIBLE_APK_BYTES) {
            runCatching { file.delete() }
            return@withContext null
        }

        // And it must actually be our app. A redirect to a login page, a mirror
        // that has been tampered with, or simply the wrong file all get this far
        // otherwise, and the next step is an install prompt.
        if (!isOurPackage(context, file)) {
            runCatching { file.delete() }
            return@withContext null
        }
        file
    }

    /** Reads the downloaded package and checks it claims to be this app. */
    private fun isOurPackage(context: Context, file: File): Boolean = try {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        info != null && info.packageName == context.packageName
    } catch (e: Exception) {
        false
    }

    /**
     * Whether this box will let the app install anything.
     *
     * From Android 8 every app needs its own permission to install packages,
     * and a sideloaded player usually does not have it yet.
     */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /**
     * Sends the viewer to the setting that grants it. Returns false when the box
     * has no such screen, which some cut-down television builds genuinely do not.
     */
    fun requestInstallPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Hands the file to Android's installer. False if nothing would take it. */
    fun install(context: Context, file: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.updates", file
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Last resort: hand the link to whatever will take it. */
    fun openInBrowser(context: Context, url: String): Boolean {
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Anything smaller than this is a redirect page or an error, not an APK. */
    private const val MIN_PLAUSIBLE_APK_BYTES = 1_000_000L
}
