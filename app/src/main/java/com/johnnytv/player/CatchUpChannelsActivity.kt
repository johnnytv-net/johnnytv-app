package com.johnnytv.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load

/**
 * The channels that can be caught up on, so a viewer sees at a glance which
 * ones the service keeps rather than having to try each. Pressing one opens
 * its list of past programmes.
 */
class CatchUpChannelsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_catch_up_channels)
        val grid = findViewById<RecyclerView>(R.id.catchUpChannels)
        val wide = resources.configuration.screenHeightDp >= 430
        grid.layoutManager = GridLayoutManager(this, if (wide) 6 else 4)
        grid.adapter = CatchUpChannelAdapter(channels()) { channel ->
            CatchUpActivity.open(this, channel)
        }
        grid.post { grid.getChildAt(0)?.requestFocus() }
    }

    companion object {
        /** Every live channel the portal says it keeps, in the app's own order. */
        fun channels(): List<StreamItem> =
            Catalog.live.filter { it.archiveDays > 0 }.inPreferredChannelOrder(Catalog.liveCategories)

        fun open(context: Context) {
            context.startActivity(Intent(context, CatchUpChannelsActivity::class.java))
        }
    }
}

class CatchUpChannelAdapter(
    private val items: List<StreamItem>,
    private val onOpen: (StreamItem) -> Unit
) : RecyclerView.Adapter<CatchUpChannelAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.catchUpLogo)
        val name: TextView = view.findViewById(R.id.catchUpChannelName)
        val days: TextView = view.findViewById(R.id.catchUpChannelDays)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_catch_up_channel, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val channel = items[position]
        val context = holder.itemView.context
        holder.name.text = channel.name
        holder.days.text = if (channel.archiveDays == 1) context.getString(R.string.catch_up_one_day)
            else context.getString(R.string.catch_up_days, channel.archiveDays)
        if (channel.icon.isBlank()) {
            holder.logo.setImageDrawable(null)
        } else {
            holder.logo.load(channel.icon) { crossfade(true) }
        }
        holder.itemView.setOnClickListener { onOpen(channel) }
    }

    override fun getItemCount(): Int = items.size
}
