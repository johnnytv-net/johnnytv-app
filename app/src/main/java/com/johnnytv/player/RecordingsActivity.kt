package com.johnnytv.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope

/**
 * RECORDINGS
 *
 * Built straight into a column rather than through a list adapter, because
 * there are tens of these and not thousands, and a plain column of focusable
 * rows is what a remote handles best - no recycling, no focus jumping to a row
 * that has just been rebound underneath it.
 *
 * Anything still recording sits at the top with its own live line, and the
 * screen refreshes itself every few seconds so somebody can watch the size
 * climb and know it is working.
 */
class RecordingsActivity : AppCompatActivity() {

    private lateinit var column: LinearLayout
    private lateinit var storageLine: TextView
    private lateinit var emptyLine: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            // Always redraw, not only while something is recording. The old
            // version stopped the moment recording did, which left the screen
            // frozen on its last drawing - so a recording somebody had just
            // stopped went on claiming to be in progress for as long as they
            // stared at it.
            draw()
            handler.postDelayed(this, 3_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings)
        column = findViewById(R.id.recordingsColumn)
        storageLine = findViewById(R.id.recordingsStorage)
        emptyLine = findViewById(R.id.recordingsEmpty)
        if (!Catalog.isLoaded) Catalog.load(this)
    }

    override fun onStart() {
        super.onStart()
        draw()
        handler.post(refresh)
        lookAroundTheHouse()
    }

    /*
     * RECORDINGS ON THE OTHER TELEVISIONS.
     *
     * Looked for once each time this screen opens, in the background, and drawn
     * under this box's own list when they arrive. A few seconds' search is not
     * worth anybody waiting for, so the screen is complete without it and the
     * section simply appears if a Shield answers.
     */
    private var elsewhere: List<ShareClient.Remote> = emptyList()

    private fun lookAroundTheHouse() {
        kotlin.concurrent.thread {
            val found = runCatching { ShareClient.everything(this) }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                elsewhere = found
                draw()
            }
        }
    }

    private fun rowFor(remote: ShareClient.Remote): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_recording, column, false)
        row.tag = "remote:" + remote.box.host + ":" + remote.id
        val clock = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
        row.findViewById<TextView>(R.id.recTitle).text = remote.title
        row.findViewById<TextView>(R.id.recDetail).text = getString(
            R.string.from_other_tv_line,
            remote.channel,
            clock.format(Date(remote.at)),
            remote.minutes
        )
        row.findViewById<TextView>(R.id.recBadge).visibility = View.GONE
        row.setOnClickListener {
            // Played straight off the other box's drive, through its playlist,
            // exactly as that box would play it itself.
            PlayerActivity.startPlaylist(
                this,
                urls = listOf(remote.playlistUrl()),
                title = remote.title,
                contentId = "remote:" + remote.id
            )
        }
        return row
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(refresh)
    }

    // ---------- drawing ----------

    private fun draw() {
        val focusedTag = currentFocus?.tag as? String
        column.removeAllViews()

        val target = Storage.chosen(this)
        storageLine.text = if (target == null) {
            getString(R.string.record_no_storage)
        } else {
            getString(
                R.string.recordings_storage,
                target.label,
                gb(target.freeBytes.toDouble())
            )
        }

        // A row left saying "Recording" after the recorder has gone is not just
        // untidy - it makes the app treat a finished recording as live, which
        // is a picture that spins at the end instead of stopping.
        for (stale in RecordingStore.all(this)) {
            if (stale.isRecording && RecorderService.activeId != stale.id) {
                RecordingStore.update(this, stale.id) {
                    it.state = STATE_DONE
                    if (it.endedAt <= 0L) it.endedAt = System.currentTimeMillis()
                }
            }
        }

        val recordings = RecordingStore.all(this)
        val scheduled = Schedules.upcoming(this)

        // The way in for a channel with no listings. PPV and event channels
        // carry no guide at all, so holding OK on them does nothing - and those
        // are precisely the channels somebody most wants to record, because a
        // fight starts at a time and nobody is going to sit through the undercard.
        column.addView(recordByTimeRow())

        emptyLine.visibility =
            if (recordings.isEmpty() && scheduled.isEmpty() && elsewhere.isEmpty()) View.VISIBLE
            else View.GONE

        for (recording in recordings) column.addView(rowFor(recording))

        if (scheduled.isNotEmpty()) {
            column.addView(heading(getString(R.string.recordings_scheduled, scheduled.size)))
            for (item in scheduled) column.addView(rowFor(item))
        }

        // Whatever the other televisions in the house have recorded, grouped
        // under the box it lives on.
        for ((boxName, group) in elsewhere.groupBy { it.box.name }) {
            column.addView(heading(getString(R.string.from_other_tv, boxName)))
            for (remote in group) column.addView(rowFor(remote))
        }

        // Put the remote back where it was, so a refresh under someone's hands
        // does not throw the highlight back to the top of the screen.
        val restore = focusedTag?.let { tag -> column.findViewWithTag<View>(tag) }
        (restore ?: column.getChildAt(0))?.requestFocus()
    }

    private fun recordByTimeRow(): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_recording, column, false)
        row.tag = "bytime"
        row.findViewById<TextView>(R.id.recTitle).setText(R.string.record_by_time)
        row.findViewById<TextView>(R.id.recDetail).setText(R.string.record_by_time_detail)
        row.findViewById<TextView>(R.id.recBadge).visibility = View.GONE
        row.setOnClickListener { RecordByTime.show(this) { draw() } }
        return row
    }

    private fun heading(text: String): View {
        val label = TextView(this)
        label.text = text
        label.setTextColor(getColor(R.color.text_secondary))
        label.textSize = 13f
        label.letterSpacing = 0.09f
        label.setPadding(dp(6), dp(18), 0, dp(8))
        label.setAllCaps(true)
        return label
    }

    private fun rowFor(recording: Recording): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_recording, column, false)
        row.tag = "rec:${recording.id}"

        val title = row.findViewById<TextView>(R.id.recTitle)
        val detail = row.findViewById<TextView>(R.id.recDetail)
        val badge = row.findViewById<TextView>(R.id.recBadge)

        title.text = recording.title
        val clock = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
        val minutes = (recording.lengthMs() / 60_000L).toInt()

        detail.text = if (recording.isRecording) {
            getString(
                R.string.recordings_in_progress,
                recording.channel,
                minutes,
                gb(recording.bytes.toDouble())
            )
        } else {
            getString(
                R.string.recordings_done_line,
                recording.channel,
                clock.format(Date(recording.startedAt)),
                minutes,
                gb(recording.bytes.toDouble())
            )
        }

        badge.text = when {
            recording.isRecording -> getString(R.string.recordings_badge_recording)
            recording.state == STATE_FAILED -> getString(R.string.recordings_badge_failed)
            recording.gaps.isEmpty() -> getString(R.string.recordings_badge_clean)
            else -> getString(
                R.string.recordings_badge_gaps,
                recording.gaps.size,
                recording.lostSeconds()
            )
        }
        badge.visibility = View.VISIBLE

        row.setOnClickListener {
            if (recording.isRecording) showRecordingOptions(recording) else play(recording)
        }
        row.setOnLongClickListener { showRecordingOptions(recording); true }
        return row
    }

    private fun rowFor(item: Scheduled): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_recording, column, false)
        row.tag = "sched:${item.id}"

        val clock = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
        row.findViewById<TextView>(R.id.recTitle).text = item.title
        row.findViewById<TextView>(R.id.recDetail).text = getString(
            R.string.recordings_scheduled_line,
            item.channel,
            clock.format(Date(item.startAt)),
            ((item.endAt - item.startAt) / 60_000L).toInt()
        )
        val badge = row.findViewById<TextView>(R.id.recBadge)
        badge.text = if (item.series) {
            getString(R.string.recordings_badge_series)
        } else {
            getString(R.string.recordings_badge_ready)
        }
        badge.visibility = View.VISIBLE

        row.setOnClickListener { confirmCancel(item) }
        row.setOnLongClickListener { confirmCancel(item); true }
        return row
    }

    // ---------- what a press does ----------

    /**
     * IS THIS PLAYLIST ACTUALLY ANY USE?
     *
     * A playlist names its parts; if those parts are not beside it, the player
     * fails on the first one and the screen drops straight back to the list -
     * which looks to anybody watching like the recording is ruined, when every
     * part is sitting on the drive perfectly intact.
     *
     * It happens after a drive has been unplugged and put back: Android gives
     * it a different name, and anything written down under the old one no
     * longer points at a real place.
     *
     * So the playlist is checked before it is trusted. A few names are looked
     * for on disk, and if they are not there the parts are played directly
     * instead - which is what the app already does when there is no playlist
     * at all.
     */
    private fun playlistLooksUsable(playlist: File): Boolean {
        val folder = playlist.parentFile ?: return false
        val named = runCatching {
            playlist.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        }.getOrNull() ?: return false

        if (named.isEmpty()) return false

        // The first, the last and one in the middle: enough to catch a folder
        // that has moved, without reading five hundred names off a slow drive.
        val toCheck = listOfNotNull(
            named.firstOrNull(),
            named.getOrNull(named.size / 2),
            named.lastOrNull()
        )
        return toCheck.all { name ->
            val part = if (name.startsWith("/")) File(name) else File(folder, name)
            part.exists() && part.length() > 0
        }
    }

    /**
     * A FINISHED RECORDING SAYS SO.
     *
     * While a recording is being made its playlist is marked EVENT, meaning
     * "still happening, more to come". A player handed one of those knows it
     * may follow along but not roam about - so skipping ahead is restricted,
     * and on a long recording it sits and spins. Half an hour gets away with
     * it; two and a half hours does not.
     *
     * New recordings now close themselves properly. This is for the ones made
     * before that, which would otherwise need Repair run on them by hand: if
     * the recording is over and its playlist still claims otherwise, the claim
     * is corrected on the way to playing it.
     */
    private fun markFinishedIfNeeded(recording: Recording) {
        runCatching {
            if (RecorderService.activeId == recording.id) return@runCatching
            val playlist = recording.playlistFile(this) ?: return@runCatching
            val text = playlist.readText()
            if (!text.contains("#EXT-X-PLAYLIST-TYPE:EVENT")) return@runCatching
            var fixed = text.replace("#EXT-X-PLAYLIST-TYPE:EVENT", "#EXT-X-PLAYLIST-TYPE:VOD")
            // And say that it ends, which EVENT playlists leave open.
            if (!fixed.contains("#EXT-X-ENDLIST")) fixed = fixed.trimEnd() + "\n#EXT-X-ENDLIST\n"
            playlist.writeText(fixed)
        }
    }

    private fun play(recording: Recording) {
        markFinishedIfNeeded(recording)

        // If it has been joined into one file, that is always the right answer.
        val whole = recording.wholeFile(this)
        if (whole != null) {
            RecordingStore.update(this, recording.id) { it.watched = true }
            PlayerActivity.startPlaylist(
                this,
                urls = listOf(android.net.Uri.fromFile(whole).toString()),
                title = recording.title,
                contentId = "rec:" + recording.id
            )
            return
        }

        val playlist = recording.playlistFile(this)
        if (playlist != null && playlistLooksUsable(playlist)) {
            RecordingStore.update(this, recording.id) { it.watched = true }
            PlayerActivity.startPlaylist(
                this,
                urls = listOf(android.net.Uri.fromFile(playlist).toString()),
                title = recording.title,
                contentId = "rec:" + recording.id
            )
            return
        }

        val files = recording.playableFiles(this)
        if (files.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.recordings_nothing_title)
                .setMessage(recording.note.ifBlank { getString(R.string.recordings_nothing_message) })
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        RecordingStore.update(this, recording.id) { it.watched = true }
        PlayerActivity.startPlaylist(
            this,
            urls = files.map { android.net.Uri.fromFile(it).toString() },
            title = recording.title,
            contentId = "rec:" + recording.id
        )
    }

    /** Every part, in order, straight to the player - no playlist involved. */
    private fun playParts(recording: Recording) {
        val files = recording.playableFiles(this)
        if (files.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.recordings_nothing_title)
                .setMessage(R.string.recordings_nothing_message)
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        RecordingStore.update(this, recording.id) { it.watched = true }
        PlayerActivity.startPlaylist(
            this,
            urls = files.map { android.net.Uri.fromFile(it).toString() },
            title = recording.title,
            contentId = "rec:" + recording.id
        )
    }

    /**
     * WHAT IS ACTUALLY IN THESE FILES?
     *
     * A recording that will not play, with no error and no crash, leaves
     * nothing to go on - and guessing has cost several builds already. This
     * opens the parts and reports what it finds: whether they can be read at
     * all, how big they are, and whether they begin the way a television
     * stream must.
     *
     * Every MPEG-TS packet starts with the byte 0x47, one every 188 bytes. A
     * file that does not is not video as far as any player is concerned,
     * however many gigabytes of it there are - and that would explain a black
     * screen far better than anything in the app.
     */
    private fun inspect(recording: Recording) {
        val working = AlertDialog.Builder(this)
            .setTitle(R.string.recordings_inspect)
            .setMessage(R.string.recordings_inspect_working)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching { inspectParts(recording) }.getOrElse { "Could not look: " + it.message }
            }
            runCatching { working.dismiss() }
            AlertDialog.Builder(this@RecordingsActivity)
                .setTitle(R.string.recordings_inspect)
                .setMessage(report)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private fun inspectParts(recording: Recording): String {
        val folder = recording.folderOnDisk(this)
        val parts = folder.listFiles { f -> f.name.matches(Regex("part\\d+\\.ts")) }
            ?.sortedBy { partNumber(it.name) }
            .orEmpty()

        val out = StringBuilder()
        out.append("Folder: ").append(folder.absolutePath).append('\n')
        out.append("Exists: ").append(folder.exists())
            .append("  readable: ").append(folder.canRead()).append('\n')
        out.append("Parts: ").append(parts.size).append('\n')
        if (parts.isEmpty()) return out.toString()

        val total = parts.sumOf { it.length() }
        out.append("Total: ").append(total / (1024 * 1024)).append(" MB\n")
        out.append("Empty parts: ").append(parts.count { it.length() == 0L }).append('\n')
        out.append('\n')

        // The first, one in the middle and the last: enough to tell whether
        // the whole recording is the same story.
        for (part in listOfNotNull(parts.firstOrNull(), parts.getOrNull(parts.size / 2), parts.lastOrNull())) {
            out.append(part.name).append("  ").append(part.length() / 1024).append(" kB  ")
            val head = runCatching {
                part.inputStream().use { stream ->
                    val buffer = ByteArray(1024)
                    val read = stream.read(buffer)
                    if (read <= 0) null else buffer.copyOf(read)
                }
            }.getOrNull()

            if (head == null) {
                out.append("could not read\n")
                continue
            }
            val syncAtStart = head[0] == 0x47.toByte()
            val syncCount = (0 until minOf(head.size, 1024) step 188).count { head[it] == 0x47.toByte() }
            out.append(if (syncAtStart) "starts correctly" else "does NOT start with 0x47")
            out.append(", ").append(syncCount).append(" of ")
                .append((minOf(head.size, 1024) + 187) / 188).append(" packets aligned\n")
        }
        return out.toString()
    }

    /**
     * ONE FILE INSTEAD OF TWO THOUSAND.
     *
     * A recording is written in pieces so that a power cut or a dropped
     * connection costs seconds rather than the lot. That is right while it is
     * being made and a nuisance afterwards: four hours interrupted by a run of
     * reconnects came out as two thousand one hundred and twenty-three pieces,
     * and no amount of playlist work has persuaded the player through them.
     *
     * A television stream can simply be laid end to end - that is what the
     * format is for - so the pieces are copied into a single file and played
     * as an ordinary video, with no playlist, no segments and nothing to go
     * wrong.
     *
     * It takes as long as copying ten gigabytes takes, and the pieces are left
     * alone until somebody says otherwise: nothing is deleted on the strength
     * of a copy that has not been watched yet.
     */
    private fun joinParts(recording: Recording) {
        val working = AlertDialog.Builder(this)
            .setTitle(R.string.recordings_join)
            .setMessage(R.string.recordings_join_working)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { joinOnDisk(recording) }
            }
            runCatching { working.dismiss() }
            val message = result.getOrNull()
                ?: getString(R.string.recordings_join_failed, result.exceptionOrNull()?.message ?: "unknown")
            AlertDialog.Builder(this@RecordingsActivity)
                .setTitle(R.string.recordings_join)
                .setMessage(message)
                .setPositiveButton(R.string.close, null)
                .show()
            draw()
        }
    }

    private fun joinOnDisk(recording: Recording): String {
        val folder = recording.folderOnDisk(this)
        val parts = folder.listFiles { f -> f.name.matches(Regex("part\\d+\\.ts")) }
            ?.sortedBy { partNumber(it.name) }
            .orEmpty()
            .filter { it.length() > 0 }

        if (parts.isEmpty()) {
            return getString(R.string.recordings_repair_nothing, folder.absolutePath,
                if (folder.exists()) "yes" else "no")
        }

        val needed = parts.sumOf { it.length() }
        val free = runCatching { folder.usableSpace }.getOrDefault(0L)
        if (free in 1 until needed + 200_000_000L) {
            return getString(
                R.string.recordings_join_no_room,
                needed / (1024 * 1024),
                free / (1024 * 1024)
            )
        }

        val whole = File(folder, WHOLE_NAME)
        runCatching { whole.delete() }

        var copied = 0L
        whole.outputStream().use { out ->
            val buffer = ByteArray(1 shl 20)
            for (part in parts) {
                part.inputStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        copied += read
                    }
                }
            }
            out.flush()
        }

        return getString(
            R.string.recordings_join_done,
            parts.size,
            copied / (1024 * 1024)
        )
    }

    /**
     * Makes an older recording into a proper video file.
     *
     * New recordings do this themselves when they finish. This is for the ones
     * made before that existed - including the four-hour one that started all
     * of it.
     */
    private fun convert(recording: Recording) {
        val working = AlertDialog.Builder(this)
            .setTitle(R.string.recordings_convert)
            .setMessage(R.string.recordings_convert_working)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val folder = recording.folderOnDisk(this@RecordingsActivity)
                var joined = File(folder, WHOLE_NAME)
                // Joining first if nobody has: no point asking twice.
                if (!joined.exists() || joined.length() < 1_000_000L) {
                    runCatching { joinOnDisk(recording) }
                    joined = File(folder, WHOLE_NAME)
                }
                Remux.toMp4(joined, File(folder, Remux.MP4_NAME))
            }
            runCatching { working.dismiss() }
            AlertDialog.Builder(this@RecordingsActivity)
                .setTitle(R.string.recordings_convert)
                .setMessage(result.message)
                .setPositiveButton(R.string.close, null)
                .show()
            draw()
        }
    }

    private fun showRecordingOptions(recording: Recording) {
        val options = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        if (recording.isRecording) {
            options.add(getString(R.string.recordings_stop))
            actions.add {
                RecorderService.stop(this)
                // The recorder may be part-way through a read when asked to
                // stop, so give it a moment and then keep an eye on it rather
                // than drawing once and trusting it.
                handler.postDelayed({ draw() }, 700L)
                handler.postDelayed({ draw() }, 3_000L)
                handler.postDelayed({ draw() }, 7_000L)
            }

            // Worth seeing while it runs, not only afterwards - it is the only
            // way to tell a recording that is going well from one that is not.
            options.add(getString(R.string.recordings_report))
            actions.add { showReport(recording) }
        } else {
            options.add(getString(R.string.recordings_play))
            actions.add { play(recording) }

            options.add(
                if (recording.keep) getString(R.string.recordings_unkeep)
                else getString(R.string.recordings_keep)
            )
            actions.add {
                RecordingStore.update(this, recording.id) { it.keep = !it.keep }
                draw()
            }

            options.add(getString(R.string.recordings_report))
            actions.add { showReport(recording) }

            // Only useful once a recording has finished: while one is still
            // being written, its playlist is meant to be incomplete.
            options.add(getString(R.string.recordings_repair))
            actions.add { repair(recording) }

            /*
             * Play it the simple way.
             *
             * The playlist is the clever route and usually the right one, but
             * when it will not work the parts themselves are still perfectly
             * good - handing them to the player one after another gets the
             * programme on screen without repairing anything first.
             */
            options.add(getString(R.string.recordings_play_parts))
            actions.add { playParts(recording) }

            options.add(getString(R.string.recordings_inspect))
            actions.add { inspect(recording) }

            options.add(getString(R.string.recordings_join))
            actions.add { joinParts(recording) }

            options.add(getString(R.string.recordings_convert))
            actions.add { convert(recording) }
        }

        // Clearing a dozen test recordings one at a time is its own small
        // misery. Anything marked Keep survives it, and so does anything still
        // recording - the point is to empty a list, not to lose something.
        val deletable = RecordingStore.all(this).filter { !it.keep && !it.isRecording }
        if (deletable.size > 1) {
            options.add(getString(R.string.recordings_delete_all, deletable.size))
            actions.add {
                val gigabytes = deletable.sumOf { it.bytes } / (1024.0 * 1024.0 * 1024.0)
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.recordings_delete_all, deletable.size))
                    .setMessage(
                        getString(
                            R.string.recordings_delete_all_confirm,
                            deletable.size,
                            String.format(Locale.getDefault(), "%.1f", gigabytes)
                        )
                    )
                    .setPositiveButton(R.string.recordings_delete) { _, _ ->
                        for (old in deletable) RecordingStore.delete(this, old.id)
                        draw()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        options.add(getString(R.string.recordings_delete))
        actions.add {
            AlertDialog.Builder(this)
                .setTitle(R.string.recordings_delete)
                .setMessage(getString(R.string.recordings_delete_confirm, recording.title))
                .setPositiveButton(R.string.recordings_delete) { _, _ ->
                    if (recording.isRecording) RecorderService.stop(this)
                    RecordingStore.delete(this, recording.id)
                    draw()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        showOptions(recording.title, options) { which -> actions[which]() }
    }

    /**
     * REBUILDING A RECORDING'S PLAYLIST, IN FRONT OF SOMEBODY.
     *
     * The automatic repair runs quietly and, when it fails, fails quietly - and
     * a quiet failure is how a four hour recording can sit there refusing to
     * play while every fix appears to have been applied. This one says what it
     * found: the folder it looked in, the parts on the drive, the entries it
     * wrote. If the answer is "nought files in a folder that does not exist",
     * that is worth far more than another guess.
     *
     * The playlist is rebuilt from scratch rather than patched. Whatever state
     * the old one had got itself into stops mattering.
     */
    private fun repair(recording: Recording) {
        /*
         * OFF THE SCREEN'S OWN THREAD.
         *
         * Repair lists every part on the drive, reads the old playlist and
         * writes a new one. On a four-hour recording that is five hundred
         * files on a USB drive that answers slowly, and doing it here stops
         * the screen responding - so Android decides the app has hung and
         * closes it. From the sofa that looks like Repair crashing the player,
         * which is exactly what was reported.
         *
         * So it happens on a background thread with something on screen to say
         * it is working, and the answer comes back when it is done.
         */
        val working = AlertDialog.Builder(this)
            .setTitle(R.string.recordings_repair)
            .setMessage(R.string.recordings_repair_working)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { rebuildPlaylist(recording) }
            }
            runCatching { working.dismiss() }

            val message = outcome.getOrNull()
                ?: getString(
                    R.string.recordings_repair_failed,
                    outcome.exceptionOrNull()?.message ?: "unknown"
                )

            AlertDialog.Builder(this@RecordingsActivity)
                .setTitle(R.string.recordings_repair)
                .setMessage(message)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    /** The work itself. Runs off the main thread; returns what to tell somebody. */
    private fun rebuildPlaylist(recording: Recording): String {
        // Wherever it really is, which is not always where it says it is.
        val folder = recording.folderOnDisk(this)
        val parts = folder.listFiles { f -> f.name.matches(Regex("part\\d+\\.ts")) }
            ?.sortedBy { partNumber(it.name) }
            .orEmpty()
            .filter { it.length() > 100_000L }

        if (parts.isEmpty()) {
            return getString(
                R.string.recordings_repair_nothing,
                folder.absolutePath,
                if (folder.exists()) "yes" else "no"
            )
        }

        // Lengths from the old playlist where it had them and they look sane;
        // from the file's own size against the rest of the recording where it
        // did not. A part announced as longer than it is stops playback dead,
        // so an estimate always errs short.
        val old = runCatching { File(folder, RecorderService.PLAYLIST_NAME).readText() }
            .getOrDefault("")
        val known = HashMap<String, Double>()
        val entries = Regex("#EXTINF:([\\d.]+),\\s*\\n(part\\d+\\.ts)").findAll(old)
        for (entry in entries) {
            val seconds = entry.groupValues[1].toDoubleOrNull() ?: continue
            if (seconds >= 1.0) known[entry.groupValues[2]] = seconds
        }
        /*
         * A LENGTH THAT CANNOT BE ABSURD.
         *
         * The first version divided total bytes by total seconds across the
         * parts whose lengths were known. One stubby part with a thirty-second
         * label - and after a run of reconnects there are plenty - drags that
         * rate down, and every estimate built on it comes out enormous. A
         * four-hour recording was rebuilt as thirty-nine days, and a player
         * handed a playlist like that gives up immediately.
         *
         * So the rate is the median of the parts we actually know, which one
         * bad entry cannot move, and no part may claim more than two minutes:
         * this recorder writes thirty-second pieces, so anything longer is
         * arithmetic rather than television.
         */
        val measured = parts.filter { known.containsKey(it.name) }
        val rates = measured.mapNotNull { part ->
            val seconds = known[part.name] ?: return@mapNotNull null
            if (seconds < 1.0) null else part.length() / seconds
        }.sorted()
        val bytesPerSecond = when {
            rates.isEmpty() -> 400_000.0
            else -> rates[rates.size / 2]
        }.coerceIn(50_000.0, 4_000_000.0)

        val lengths = parts.map { part ->
            val seconds = known[part.name] ?: (part.length() / bytesPerSecond * 0.97)
            // Nothing here is longer than two minutes; the recorder writes in
            // thirty-second pieces and a reconnect makes them shorter, never
            // longer.
            part.name to seconds.coerceIn(1.0, 120.0)
        }

        val text = StringBuilder()
        text.append("#EXTM3U\n#EXT-X-VERSION:3\n")
        text.append("#EXT-X-TARGETDURATION:")
            .append(Math.ceil(lengths.maxOf { it.second }).toInt()).append("\n")
        text.append("#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
        /*
         * A DISCONTINUITY BETWEEN EVERY PART IS ITS OWN PROBLEM.
         *
         * It tells the player to throw away its timing and start again, which
         * is right where the recording genuinely jumped and wrong everywhere
         * else. Across two thousand parts it is thousands of restarts, and
         * enough on its own to stop a recording playing.
         *
         * Repair cannot know where the real gaps were - that knowledge was in
         * the playlist it is replacing - so it claims none, which plays through
         * cleanly and at worst gets the clock slightly wrong over a join.
         */
        for ((index, part) in lengths.withIndex()) {
            text.append("#EXTINF:")
                .append(String.format(Locale.US, "%.3f", part.second))
                .append(",\n").append(part.first).append("\n")
        }
        text.append("#EXT-X-ENDLIST\n")

        val written = runCatching {
            File(folder, RecorderService.PLAYLIST_NAME).writeText(text.toString())
            true
        }.getOrDefault(false)

        return getString(
            R.string.recordings_repair_done,
            parts.size,
            (lengths.sumOf { it.second } / 60).toInt(),
            if (written) "written" else "COULD NOT WRITE",
            folder.absolutePath
        )
    }

    private fun showReport(recording: Recording) {
        val clock = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
        val lines = ArrayList<String>()
        if (recording.gaps.isEmpty()) {
            lines.add(getString(R.string.recordings_report_clean))
        } else {
            // A run of blips close together is one event, not twenty. Reading
            // twelve identical lines tells nobody anything except that the
            // screen is full; "8:47 to 8:50, 12 blips, 12 seconds lost" is the
            // same information in a sentence.
            var runStart = recording.gaps.first()
            var runEnd = runStart
            var count = 0
            var lost = 0

            fun flush() {
                if (count == 0) return
                if (count == 1) {
                    lines.add(
                        getString(
                            R.string.recordings_report_gap,
                            clock.format(Date(runStart.at)),
                            runStart.seconds
                        )
                    )
                } else {
                    lines.add(
                        getString(
                            R.string.recordings_report_run,
                            clock.format(Date(runStart.at)),
                            clock.format(Date(runEnd.at)),
                            count,
                            lost
                        )
                    )
                }
            }

            for (gap in recording.gaps) {
                if (count > 0 && gap.at - runEnd.at > RUN_TOGETHER_MS) {
                    flush()
                    count = 0
                    lost = 0
                    runStart = gap
                }
                if (count == 0) runStart = gap
                runEnd = gap
                count++
                lost += gap.seconds
            }
            flush()
        }
        if (recording.note.isNotBlank()) lines.add(recording.note)
        AlertDialog.Builder(this)
            .setTitle(R.string.recordings_report)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun confirmCancel(item: Scheduled) {
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setMessage(getString(R.string.recordings_cancel_confirm))
            .setPositiveButton(R.string.recordings_cancel_yes) { _, _ ->
                Schedules.remove(this, item.id)
                draw()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- odds and ends ----------

    private fun gb(bytes: Double): String {
        val value = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (value >= 10) String.format(Locale.getDefault(), "%.0f GB", value)
        else String.format(Locale.getDefault(), "%.1f GB", value)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** Blips closer together than this are read as one bad patch. */
        private const val RUN_TOGETHER_MS = 2L * 60L * 1000L

        fun open(context: android.content.Context) {
            context.startActivity(Intent(context, RecordingsActivity::class.java))
        }
    }
}
