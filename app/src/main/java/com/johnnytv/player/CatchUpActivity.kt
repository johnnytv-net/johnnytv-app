package com.johnnytv.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Catch-up for one channel: the days the portal still holds along the top,
 * that day's programmes below, OK to watch one from its beginning.
 *
 * Nothing is stored on the box - the portal keeps the programmes and plays
 * them back like a film - so this works the same on a Firestick as on a
 * Shield.
 */
class CatchUpActivity : AppCompatActivity() {

    private lateinit var days: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var progress: View

    private var streamId = ""
    private var channelName = ""
    private var byDay: List<Pair<String, List<CatchUpShow>>> = emptyList()

    private val adapter = CatchUpAdapter { show -> play(show) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_catch_up)
        streamId = intent.getStringExtra(EXTRA_STREAM).orEmpty()
        channelName = intent.getStringExtra(EXTRA_NAME).orEmpty()

        days = findViewById(R.id.catchUpDays)
        list = findViewById(R.id.catchUpList)
        empty = findViewById(R.id.catchUpEmpty)
        progress = findViewById(R.id.catchUpProgress)
        findViewById<TextView>(R.id.catchUpTitle).text = getString(R.string.catch_up_title, channelName)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        load()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        val archiveDays = Catalog.live.firstOrNull { it.streamId == streamId }?.archiveDays ?: -1
        // Written to Settings > Last playback, so an empty list can explain itself.
        PlaybackLog.start(this, "catch-up list: " + channelName + " (" + archiveDays + " days, id " + streamId + ")")
        lifecycleScope.launch {
            val shows = withContext(Dispatchers.IO) {
                runCatching {
                    Prefs(this@CatchUpActivity).client().catchUp(streamId, archiveDays) { line ->
                        PlaybackLog.add(this@CatchUpActivity, line)
                    }
                }
            }
            shows.exceptionOrNull()?.let { PlaybackLog.add(this@CatchUpActivity, "failed: " + it) }
            if (isFinishing || isDestroyed) return@launch
            progress.visibility = View.GONE
            val found = shows.getOrNull()
            if (found == null || found.isEmpty()) {
                empty.setText(if (found == null) R.string.catch_up_failed else R.string.catch_up_none)
                empty.visibility = View.VISIBLE
                return@launch
            }
            byDay = groupByDay(found)
            buildDayTabs()
            selectDay(0)
            list.post { list.getChildAt(0)?.requestFocus() }
        }
    }

    /** Newest day first, and within a day the latest programme first. */
    private fun groupByDay(shows: List<CatchUpShow>): List<Pair<String, List<CatchUpShow>>> {
        val out = LinkedHashMap<String, MutableList<CatchUpShow>>()
        val today = dayNumber(System.currentTimeMillis())
        val named = SimpleDateFormat("EEE d MMM", Locale.getDefault())
        for (show in shows) {
            val label = when (today - dayNumber(show.start)) {
                0L -> getString(R.string.catch_up_today)
                1L -> getString(R.string.catch_up_yesterday)
                else -> named.format(Date(show.start))
            }
            out.getOrPut(label) { ArrayList() }.add(show)
        }
        return out.map { it.key to it.value }
    }

    /** Days counted on the viewer's own calendar, so "Today" means their today. */
    private fun dayNumber(at: Long): Long {
        val local = at + TimeZone.getDefault().getOffset(at)
        return Math.floorDiv(local, 24L * 60L * 60L * 1000L)
    }

    private fun buildDayTabs() {
        days.removeAllViews()
        val density = resources.displayMetrics.density
        byDay.forEachIndexed { index, (label, _) ->
            val tab = TextView(this)
            tab.text = label
            tab.textSize = 15f
            tab.setTextColor(getColor(R.color.text_primary))
            tab.setBackgroundResource(R.drawable.bg_tab)
            tab.isFocusable = true
            tab.isClickable = true
            val side = (20 * density).toInt()
            val edge = (9 * density).toInt()
            tab.setPadding(side, edge, side, edge)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = (8 * density).toInt()
            tab.layoutParams = params
            tab.setOnClickListener { selectDay(index) }
            days.addView(tab)
        }
    }

    private var shownDay = -1

    private fun selectDay(index: Int) {
        if (index == shownDay) return
        shownDay = index
        for (i in 0 until days.childCount) days.getChildAt(i).isActivated = i == index
        adapter.show(byDay.getOrNull(index)?.second.orEmpty())
        list.scrollToPosition(0)
    }

    private fun play(show: CatchUpShow) {
        // A one-connection line cannot record and play something else at once.
        if (RecorderService.isRecording) {
            Toast.makeText(this, R.string.record_busy_watching, Toast.LENGTH_LONG).show()
            return
        }
        val urls = Prefs(this).client().catchUpUrls(streamId, show)
        if (urls.isEmpty()) {
            Toast.makeText(this, getString(R.string.could_not_play, show.title), Toast.LENGTH_LONG).show()
            return
        }
        PlayerActivity.start(
            this,
            urls = urls,
            title = show.title,
            kind = Kind.VOD,
            contentId = CONTENT_PREFIX + streamId + ":" + show.start
        )
    }

    companion object {
        private const val EXTRA_STREAM = "extra_stream"
        private const val EXTRA_NAME = "extra_name"

        /** Marks what the player is showing as catch-up rather than a film. */
        const val CONTENT_PREFIX = "cu:"

        fun open(context: Context, channel: StreamItem) {
            context.startActivity(
                Intent(context, CatchUpActivity::class.java)
                    .putExtra(EXTRA_STREAM, channel.streamId)
                    .putExtra(EXTRA_NAME, channel.name)
            )
        }
    }
}

class CatchUpAdapter(private val onPlay: (CatchUpShow) -> Unit) : RecyclerView.Adapter<CatchUpAdapter.VH>() {

    private var items: List<CatchUpShow> = emptyList()
    private val clock = SimpleDateFormat("h:mm a", Locale.getDefault())

    fun show(list: List<CatchUpShow>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.catchUpTime)
        val name: TextView = view.findViewById(R.id.catchUpName)
        val about: TextView = view.findViewById(R.id.catchUpAbout)
        val note: TextView = view.findViewById(R.id.catchUpNote)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_catch_up, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val show = items[position]
        val context = holder.itemView.context
        holder.time.text = clock.format(Date(show.start))
        holder.name.text = show.title
        holder.about.text = show.description
        holder.about.visibility = if (show.description.isBlank()) View.GONE else View.VISIBLE
        val running = System.currentTimeMillis() < show.end
        val minutes = ((show.end - show.start) / 60_000L).toInt()
        holder.note.text = if (running) context.getString(R.string.catch_up_on_now)
            else context.getString(R.string.catch_up_minutes, minutes)
        holder.note.setTextColor(
            context.getColor(if (running) R.color.brand_accent else R.color.text_secondary)
        )
        holder.itemView.setOnClickListener { onPlay(show) }
    }

    override fun getItemCount(): Int = items.size
}
