package com.johnnytv.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * KEEPING A CHANNEL.
 *
 * Recording here is not clever and deliberately so: it opens the same stream the
 * player opens and writes the bytes to a file, untouched. No re-encoding, no
 * repackaging - a transport stream is already a format that can be cut anywhere
 * and still play, which is why a recording that is interrupted half way through
 * is still watchable up to the point it stopped.
 *
 * It runs as a service rather than inside the player because the two are
 * separate jobs. Somebody should be able to start a recording, leave the
 * channel, watch something else, or put the television on standby, and come
 * back to a finished recording. Tying it to a screen would mean losing it the
 * moment they pressed Back.
 *
 * The honest cost, stated here because it surprises people: this is a second
 * connection to the service. A line that allows one screen cannot record one
 * channel and show another at the same time, and on such a line recording while
 * watching will fight with the picture. Nothing in the app can change that - it
 * is the same on every player - so the app says so plainly instead of pretending.
 */
class RecordingService : Service() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // No read timeout: a live stream is meant to never end.
        .readTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var stopping = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopping = true
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // One at a time. A second request while something is already recording is
        // treated as a mistake rather than quietly replacing what is running.
        if (worker != null) return START_STICKY

        val urls = intent?.getStringArrayListExtra(EXTRA_URLS) ?: arrayListOf()
        val channelName = intent?.getStringExtra(EXTRA_CHANNEL_NAME).orEmpty()
        val channelId = intent?.getStringExtra(EXTRA_CHANNEL_ID).orEmpty()
        val programme = intent?.getStringExtra(EXTRA_PROGRAMME).orEmpty()
        if (urls.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val target = Recordings.newFile(this, channelName)
        state = State(channelId, channelName, programme, target, System.currentTimeMillis())
        val notification = buildNotification(channelName, programme)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        notifyListeners()

        worker = thread(name = "johnnytv-recorder") { record(urls, target, channelName, programme) }
        return START_STICKY
    }

    /**
     * The actual copying.
     *
     * Every container the portal might offer is tried in turn, the same way the
     * player tries them, because a service that only speaks one dialect would
     * fail on half the channels the app can already play.
     */
    private fun record(urls: List<String>, target: File, channelName: String, programme: String) {
        val startedAt = System.currentTimeMillis()
        var wrote = 0L
        var opened = false

        for (url in urls) {
            if (stopping) break
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", Config.USER_AGENT)
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body ?: return@use
                    opened = true
                    FileOutputStream(target, true).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        val input = body.byteStream()
                        var lastCheck = System.currentTimeMillis()
                        while (!stopping) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            wrote += read

                            // Disk and clock are checked every few seconds rather
                            // than every block - the answer cannot change fast
                            // enough to be worth asking sixteen times a second.
                            val now = System.currentTimeMillis()
                            if (now - lastCheck > 5_000L) {
                                lastCheck = now
                                if (now - startedAt > Recordings.MAX_LENGTH_MS) break
                                if (Recordings.freeSpace(this@RecordingService) < Recordings.FREE_SPACE_FLOOR) break
                                Recordings.writeNote(
                                    target, channelName, programme, startedAt, now, finished = false
                                )
                            }
                        }
                        out.flush()
                    }
                }
            } catch (e: Exception) {
                // A dropped feed is not a reason to lose what has already been
                // written. Move on to the next address, or finish with what we have.
            }
            if (opened) break
        }

        Recordings.writeNote(
            target, channelName, programme, startedAt, System.currentTimeMillis(), finished = true
        )
        // Nothing arrived at all - do not leave an empty file pretending to be a
        // recording somebody can watch.
        if (wrote == 0L) runCatching { target.delete() }

        lastResult = if (wrote == 0L) Result.FAILED else Result.SAVED
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        state = null
        notifyListeners()
        runCatching { worker?.join(2_000L) }
        worker = null
        super.onDestroy()
    }

    // ---------- the notification ----------

    private fun buildNotification(channelName: String, programme: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recording_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, RecordingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.recording_now))
            .setContentText(if (programme.isBlank()) channelName else "$channelName · $programme")
            .setSmallIcon(R.drawable.ic_record)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?,
                    getString(R.string.stop_recording),
                    stop
                ).build()
            )
            .build()
    }

    data class State(
        val channelId: String,
        val channelName: String,
        val programme: String,
        val file: File,
        val startedAt: Long
    )

    enum class Result { SAVED, FAILED }

    companion object {
        const val ACTION_STOP = "com.johnnytv.player.STOP_RECORDING"
        const val EXTRA_URLS = "urls"
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_PROGRAMME = "programme"

        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 4801

        /** What is recording right now, or null. Read by the player to show its state. */
        @Volatile
        var state: State? = null
            private set

        /** How the last recording ended, so the screen that started it can say so. */
        @Volatile
        var lastResult: Result? = null

        private val listeners = mutableListOf<() -> Unit>()

        fun watch(listener: () -> Unit) { synchronized(listeners) { listeners.add(listener) } }
        fun unwatch(listener: () -> Unit) { synchronized(listeners) { listeners.remove(listener) } }

        private fun notifyListeners() {
            val copy = synchronized(listeners) { listeners.toList() }
            copy.forEach { runCatching { it() } }
        }

        fun isRecording(channelId: String): Boolean = state?.channelId == channelId

        fun start(
            context: Context,
            urls: List<String>,
            channelId: String,
            channelName: String,
            programme: String
        ) {
            val intent = Intent(context, RecordingService::class.java)
                .putStringArrayListExtra(EXTRA_URLS, ArrayList(urls))
                .putExtra(EXTRA_CHANNEL_ID, channelId)
                .putExtra(EXTRA_CHANNEL_NAME, channelName)
                .putExtra(EXTRA_PROGRAMME, programme)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
