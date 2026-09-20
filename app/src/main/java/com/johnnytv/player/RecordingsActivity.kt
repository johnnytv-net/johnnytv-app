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
            if (recordings.isEmpty() && scheduled.isEmpty()) View.VISIBLE else View.GONE

        for (recording in recordings) column.addView(rowFor(recording))

        if (scheduled.isNotEmpty()) {
            column.addView(heading(getString(R.string.recordings_scheduled, scheduled.size)))
            for (item in scheduled) column.addView(rowFor(item))
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

    private fun play(recording: Recording) {
        val playlist = recording.playlistFile()
        if (playlist != null) {
            RecordingStore.update(this, recording.id) { it.watched = true }
            PlayerActivity.startPlaylist(
                this,
                urls = listOf(android.net.Uri.fromFile(playlist).toString()),
                title = recording.title,
                contentId = "rec:" + recording.id
            )
            return
        }

        val files = recording.playableFiles()
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

            options.add(getString(R.string.recordings_repair))
            actions.add { repair(recording) }
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
        val folder = java.io.File(recording.dirPath)
        val parts = folder.listFiles { f -> f.name.matches(Regex("part\\d+\\.ts")) }
            ?.sortedBy { it.name }
            .orEmpty()
            .filter { it.length() > 100_000L }

        if (parts.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.recordings_repair)
                .setMessage(
                    getString(
                        R.string.recordings_repair_nothing,
                        recording.dirPath,
                        if (folder.exists()) "yes" else "no"
                    )
                )
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }

        // Lengths from the old playlist where it had them and they look sane;
        // from the file's own size against the rest of the recording where it
        // did not. A part announced as longer than it is stops playback dead,
        // so an estimate always errs short.
        val old = runCatching { java.io.File(folder, RecorderService.PLAYLIST_NAME).readText() }
            .getOrDefault("")
        val known = HashMap<String, Double>()
        val entries = Regex("#EXTINF:([\\d.]+),\\s*\\n(part\\d+\\.ts)").findAll(old)
        for (entry in entries) {
            val seconds = entry.groupValues[1].toDoubleOrNull() ?: continue
            if (seconds >= 1.0) known[entry.groupValues[2]] = seconds
        }
        val measured = parts.filter { known.containsKey(it.name) }
        val bytesPerSecond = if (measured.isNotEmpty()) {
            measured.sumOf { it.length() }.toDouble() /
                measured.sumOf { known[it.name] ?: 0.0 }.coerceAtLeast(1.0)
        } else {
            400_000.0
        }

        val lengths = parts.map { part ->
            val seconds = known[part.name] ?: (part.length() / bytesPerSecond * 0.97)
            part.name to seconds.coerceAtLeast(1.0)
        }

        val text = StringBuilder()
        text.append("#EXTM3U\n#EXT-X-VERSION:3\n")
        text.append("#EXT-X-TARGETDURATION:")
            .append(Math.ceil(lengths.maxOf { it.second }).toInt()).append("\n")
        text.append("#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
        for ((index, part) in lengths.withIndex()) {
            if (index > 0) text.append("#EXT-X-DISCONTINUITY\n")
            text.append("#EXTINF:")
                .append(String.format(Locale.US, "%.3f", part.second))
                .append(",\n").append(part.first).append("\n")
        }
        text.append("#EXT-X-ENDLIST\n")

        val written = runCatching {
            java.io.File(folder, RecorderService.PLAYLIST_NAME).writeText(text.toString())
            true
        }.getOrDefault(false)

        AlertDialog.Builder(this)
            .setTitle(R.string.recordings_repair)
            .setMessage(
                getString(
                    R.string.recordings_repair_done,
                    parts.size,
                    (lengths.sumOf { it.second } / 60).toInt(),
                    if (written) "written" else "COULD NOT WRITE",
                    recording.dirPath
                )
            )
            .setPositiveButton(R.string.close, null)
            .show()
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
