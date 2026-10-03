package com.johnnytv.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Choosing the teams to follow: a league along the top, its teams below, OK
 * to add one or take it off again.
 */
class MyTeamsActivity : AppCompatActivity() {

    private lateinit var tabs: LinearLayout
    private lateinit var grid: RecyclerView
    private lateinit var progress: View
    private lateinit var countLabel: TextView
    private var league = MyTeams.LEAGUES.first()

    private val adapter = TeamAdapter { team ->
        MyTeams.toggle(this, team)
        refreshChosen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_teams)
        tabs = findViewById(R.id.teamsTabs)
        grid = findViewById(R.id.teamsGrid)
        progress = findViewById(R.id.teamsProgress)
        countLabel = findViewById(R.id.teamsCount)

        val wide = resources.configuration.screenHeightDp >= 430
        grid.layoutManager = GridLayoutManager(this, if (wide) 6 else 4)
        grid.adapter = adapter

        val density = resources.displayMetrics.density
        for (name in MyTeams.LEAGUES) {
            val tab = TextView(this)
            tab.text = name
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
            tab.setOnClickListener { select(name) }
            tabs.addView(tab)
        }
        select(league)
        tabs.getChildAt(0)?.requestFocus()
    }

    private fun select(name: String) {
        league = name
        for (i in 0 until tabs.childCount) {
            val tab = tabs.getChildAt(i) as TextView
            tab.isActivated = tab.text == name
        }
        progress.visibility = View.VISIBLE
        adapter.show(emptyList(), emptySet())
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { MyTeams.teams(this@MyTeamsActivity, name) }.getOrDefault(emptyList())
            }
            if (isFinishing || isDestroyed || league != name) return@launch
            progress.visibility = View.GONE
            adapter.show(list, chosenKeys())
            refreshChosen()
        }
    }

    private fun chosenKeys(): Set<String> = MyTeams.chosen(this).map { it.key }.toSet()

    private fun refreshChosen() {
        val keys = chosenKeys()
        adapter.mark(keys)
        countLabel.text = resources.getQuantityString(R.plurals.teams_chosen, keys.size, keys.size)
    }
}

class TeamAdapter(private val onToggle: (Team) -> Unit) : RecyclerView.Adapter<TeamAdapter.VH>() {

    private var items: List<Team> = emptyList()
    private var chosen: Set<String> = emptySet()

    fun show(list: List<Team>, keys: Set<String>) {
        items = list
        chosen = keys
        notifyDataSetChanged()
    }

    fun mark(keys: Set<String>) {
        val before = chosen
        chosen = keys
        items.forEachIndexed { index, team ->
            if (before.contains(team.key) != keys.contains(team.key)) notifyItemChanged(index)
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.teamLogo)
        val name: TextView = view.findViewById(R.id.teamName)
        val tick: View = view.findViewById(R.id.teamTick)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_team, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val team = items[position]
        val picked = chosen.contains(team.key)
        holder.name.text = team.name
        holder.tick.visibility = if (picked) View.VISIBLE else View.GONE
        holder.itemView.isActivated = picked
        if (team.logo.isBlank()) {
            holder.logo.visibility = View.GONE
        } else {
            holder.logo.visibility = View.VISIBLE
            holder.logo.load(team.logo) { crossfade(true) }
        }
        holder.itemView.setOnClickListener { onToggle(team) }
    }

    override fun getItemCount(): Int = items.size
}
