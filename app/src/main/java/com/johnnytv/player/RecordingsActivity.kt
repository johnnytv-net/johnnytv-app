package com.johnnytv.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * THE RECORDINGS SHELF.
 *
 * A flat list, newest first, and nothing else. There is no folder structure and
 * no sorting to choose, because a television remote makes both of those a chore
 * and because the thing somebody wants is nearly always the thing they recorded
 * last.
 *
 * Deleting is behind a long press rather than a button on the row. On a remote
 * the only thing between "play" and "delete for ever" would otherwise be one
 * accidental sideways press, and these files cannot be recovered.
 */
class RecordingsActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var space: TextView
    private var items: List<Recordings.Recording> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings)
        list = findViewById(R.id.recordingsList)
        empty = findViewById(R.id.recordingsEmpty)
        space = findViewById(R.id.recordingsSpace)
        list.layoutManager = LinearLayoutManager(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        items = Recordings.list(this)
        list.adapter = Adapter()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        space.text = getString(
            R.string.space_free,
            Recordings.sizeText(Recordings.freeSpace(this))
        )
        if (items.isNotEmpty()) list.post { list.getChildAt(0)?.requestFocus() }
    }

    private fun play(recording: Recordings.Recording) {
        PlayerActivity.start(
            this,
            // A local file needs no portal and no second connection; it is handed
            // to the player exactly as a stream address would be.
            urls = listOf(android.net.Uri.fromFile(recording.file).toString()),
            title = recording.title,
            kind = Kind.VOD,
            contentId = "rec:" + recording.file.name
        )
    }

    private fun askDelete(recording: Recordings.Recording) {
        AlertDialog.Builder(this)
            .setTitle(recording.title)
            .setMessage(getString(R.string.delete_recording_confirm))
            .setPositiveButton(R.string.delete) { _, _ ->
                Recordings.delete(recording)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_recording, parent, false)
            )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            val parts = ArrayList<String>(4)
            if (item.programme.isNotBlank()) parts.add(item.channel)
            parts.add(Recordings.whenText(item.startedAt))
            if (item.lengthMs > 60_000L) parts.add(Recordings.lengthText(item.lengthMs))
            parts.add(Recordings.sizeText(item.bytes))
            holder.meta.text = parts.joinToString("  ·  ")

            holder.itemView.setOnClickListener { play(item) }
            holder.itemView.setOnLongClickListener { askDelete(item); true }
        }
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.recTitle)
        val meta: TextView = view.findViewById(R.id.recMeta)
    }
}
