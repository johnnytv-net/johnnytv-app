package com.johnnytv.player

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One series: cover, seasons across the top, episodes below. */
class SeriesActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var seriesId: String

    private lateinit var cover: ImageView
    private lateinit var titleLabel: TextView
    private lateinit var plotLabel: TextView
    private lateinit var seasonRow: LinearLayout
    private lateinit var episodeList: RecyclerView
    private lateinit var progress: View
    private lateinit var statusLabel: TextView
    private lateinit var favouriteButton: TextView

    private lateinit var episodeAdapter: EpisodeAdapter
    private var seasons: Map<Int, List<Episode>> = emptyMap()
    private var selectedSeason: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: ""

        setContentView(R.layout.activity_series)
        cover = findViewById(R.id.seriesCover)
        titleLabel = findViewById(R.id.seriesTitle)
        plotLabel = findViewById(R.id.seriesPlot)
        seasonRow = findViewById(R.id.seasonRow)
        episodeList = findViewById(R.id.episodeList)
        progress = findViewById(R.id.seriesProgress)
        statusLabel = findViewById(R.id.seriesStatus)
        favouriteButton = findViewById(R.id.seriesFavourite)

        titleLabel.text = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val coverUrl = intent.getStringExtra(EXTRA_COVER) ?: ""
        // No placeholder: the JohnnyTV mark sits underneath and shows wherever
        // a poster is missing or will not load. The poster is cut to its
        // card's rounded corners.
        findViewById<View>(R.id.seriesCoverFrame).clipToOutline = true
        if (coverUrl.isNotBlank()) cover.load(coverUrl) { crossfade(true) }

        val shortScreen = resources.configuration.screenHeightDp < 430
        if (shortScreen) {
            // A phone on its side: the same page, a size down.
            titleLabel.textSize = 22f
            titleLabel.maxLines = 1
            plotLabel.maxLines = 2
        }

        val item = Catalog.series.firstOrNull { it.seriesId == seriesId }
        plotLabel.text = item?.plot ?: ""
        plotLabel.visibility = if (plotLabel.text.isBlank()) View.GONE else View.VISIBLE

        // The series' own backdrop, where the portal sent one.
        val backdropUrl = item?.backdrop.orEmpty()
        if (backdropUrl.isNotBlank()) {
            findViewById<ImageView>(R.id.seriesBackdrop).load(backdropUrl) { crossfade(true) }
        }

        // The facts as tags, exactly as on a film: the rating first and in
        // gold, then the year and up to three genres. Only the ones that exist.
        val facts = findViewById<LinearLayout>(R.id.seriesFacts)
        val light = getColor(R.color.text_primary)
        val size = if (shortScreen) 12f else 15f
        val rating = item?.rating?.trim()?.toDoubleOrNull()
        if (rating != null && rating > 0.0) {
            facts.addView(
                tag("\u2605 " + String.format(java.util.Locale.US, "%.1f", rating),
                    R.drawable.bg_fact_rating, 0xFF14181D.toInt(), true, size)
            )
        }
        item?.year?.take(4)?.takeIf { it.length == 4 && it.all { c -> c.isDigit() } }
            ?.let { facts.addView(tag(it, R.drawable.bg_fact_chip, light, false, size)) }
        item?.genre.orEmpty().split(',', '/', '|').map { it.trim() }.filter { it.isNotBlank() }.take(3)
            .forEach { facts.addView(tag(it, R.drawable.bg_fact_chip, light, false, size)) }
        facts.visibility = if (facts.childCount == 0) View.GONE else View.VISIBLE

        episodeAdapter = EpisodeAdapter { episode -> play(episode) }
        episodeList.layoutManager = LinearLayoutManager(this)
        episodeList.adapter = episodeAdapter

        updateFavouriteLabel()
        favouriteButton.setOnClickListener {
            prefs.toggleFavourite(Kind.SERIES, seriesId)
            updateFavouriteLabel()
        }

        loadEpisodes()
    }

    override fun onResume() {
        super.onResume()
        if (selectedSeason > 0) showSeason(selectedSeason)
    }

    private fun updateFavouriteLabel() {
        val isFavourite = prefs.isFavourite(Kind.SERIES, seriesId)
        // A gold star when it is in, a hollow one when it is not - the same star
        // that then appears on the tile and counts towards FAVOURITES.
        favouriteButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (isFavourite) R.drawable.ic_star_filled else R.drawable.ic_star_outline,
            0, 0, 0
        )
        favouriteButton.text = getString(
            if (isFavourite) R.string.in_favourites else R.string.add_to_favourites
        )
        favouriteButton.isActivated = isFavourite
    }

    private fun loadEpisodes() {
        progress.visibility = View.VISIBLE
        statusLabel.visibility = View.GONE

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { prefs.client().seriesEpisodes(seriesId) }
            }
            progress.visibility = View.GONE

            result.onSuccess { loaded ->
                seasons = loaded
                if (loaded.isEmpty()) {
                    statusLabel.text = getString(R.string.no_episodes)
                    statusLabel.visibility = View.VISIBLE
                    return@onSuccess
                }
                buildSeasonRow()
                showSeason(loaded.keys.first())
            }.onFailure { error ->
                statusLabel.text = error.message ?: getString(R.string.no_episodes)
                statusLabel.visibility = View.VISIBLE
            }
        }
    }

    private fun buildSeasonRow() {
        seasonRow.removeAllViews()
        for (season in seasons.keys) {
            val button = TextView(this)
            button.text = getString(R.string.season_number, season)
            button.setBackgroundResource(R.drawable.bg_tab)
            button.setTextColor(getColor(R.color.text_primary))
            button.textSize = 14f
            button.gravity = Gravity.CENTER
            button.isFocusable = true
            button.isClickable = true
            button.setPadding(dp(18), dp(10), dp(18), dp(10))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.rightMargin = dp(8)
            button.layoutParams = params
            button.tag = season
            button.setOnClickListener { showSeason(season) }
            seasonRow.addView(button)
        }
    }

    private fun showSeason(season: Int) {
        selectedSeason = season
        for (i in 0 until seasonRow.childCount) {
            val child = seasonRow.getChildAt(i)
            child.isActivated = child.tag == season
        }
        val episodes = seasons[season].orEmpty()
        val partWatched = episodes.filter { prefs.position(Kind.SERIES, it.episodeId) > 0 }
            .map { it.episodeId }
            .toSet()
        episodeAdapter.submit(episodes, partWatched)
    }

    private fun play(episode: Episode) {
        // Remember the series itself as part-watched so it shows in Continue Watching.
        prefs.savePosition(Kind.SERIES, seriesId, 60_001L, 0L)
        PlayerActivity.start(
            this,
            urls = prefs.client().episodeUrls(episode.episodeId, episode.containerExtension),
            title = "${titleLabel.text} - ${episode.title}",
            kind = Kind.SERIES,
            contentId = episode.episodeId
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** One fact as a tag. */
    private fun tag(text: String, background: Int, color: Int, bold: Boolean, size: Float): TextView {
        val label = TextView(this)
        label.text = text
        label.textSize = size
        label.setTextColor(color)
        if (bold) label.setTypeface(label.typeface, android.graphics.Typeface.BOLD)
        label.setBackgroundResource(background)
        label.maxLines = 1
        label.setPadding(dp(12), dp(5), dp(12), dp(5))
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.marginEnd = dp(8)
        label.layoutParams = params
        return label
    }

    companion object {
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_TITLE = "series_title"
        const val EXTRA_COVER = "series_cover"
    }
}
