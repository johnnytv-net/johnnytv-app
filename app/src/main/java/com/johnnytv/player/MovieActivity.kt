package com.johnnytv.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ONE FILM, BEFORE YOU COMMIT TO IT.
 *
 * Pressing a poster used to start the film immediately, which is fine when you
 * already know what it is and useless when you do not - a wall of posters gives
 * you a title and nothing else, so choosing meant starting films to find out
 * what they were and backing out again.
 *
 * So the poster opens this instead: what it is about, who is in it, what it is
 * rated and how long it runs. Watch is already under the remote when the screen
 * appears, so for anyone who did know what they wanted it is still one press -
 * just one press that now happens a moment later.
 *
 * Everything here comes from a single request to the portal, and every field of
 * it is optional. A portal that sends nothing but a plot shows a plot; the rest
 * quietly disappears rather than leaving empty labels behind.
 */
class MovieActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var movieId: String
    private var containerExtension: String = "mp4"

    private lateinit var poster: ImageView
    private lateinit var backdrop: ImageView
    private lateinit var titleLabel: TextView
    private lateinit var factsRow: LinearLayout
    private lateinit var plotLabel: TextView
    private lateinit var castBlock: View
    private lateinit var castTitle: View
    private lateinit var castRow: LinearLayout
    private lateinit var directorLabel: TextView
    private lateinit var resumeBlock: View
    private lateinit var resumeText: TextView

    /** The film's length in minutes, once the portal has said. */
    private var runtimeMinutes = 0

    /** A phone on its side, rather than a television or a tablet. */
    private val shortScreen: Boolean
        get() = resources.configuration.screenHeightDp < 430
    private lateinit var watchButton: TextView
    private lateinit var favouriteButton: TextView
    private lateinit var restartButton: TextView
    private lateinit var progress: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        movieId = intent.getStringExtra(EXTRA_MOVIE_ID) ?: ""
        containerExtension = intent.getStringExtra(EXTRA_EXTENSION) ?: "mp4"

        setContentView(R.layout.activity_movie)
        poster = findViewById(R.id.moviePoster)
        backdrop = findViewById(R.id.movieBackdrop)
        titleLabel = findViewById(R.id.movieTitle)
        factsRow = findViewById(R.id.movieFacts)
        plotLabel = findViewById(R.id.moviePlot)
        castBlock = findViewById(R.id.movieCastBlock)
        castTitle = findViewById(R.id.movieCastTitle)
        castRow = findViewById(R.id.movieCastRow)
        directorLabel = findViewById(R.id.movieDirector)
        resumeBlock = findViewById(R.id.movieResumeBlock)
        resumeText = findViewById(R.id.movieResumeText)
        // The poster is cut to its card's rounded corners.
        findViewById<View>(R.id.moviePosterFrame).clipToOutline = true
        if (shortScreen) {
            // Two thirds of the height to work with: the same page, a size down.
            titleLabel.textSize = 24f
            titleLabel.maxLines = 1
            plotLabel.textSize = 13f
            plotLabel.maxLines = 3
        }
        watchButton = findViewById(R.id.movieWatch)
        favouriteButton = findViewById(R.id.movieFavourite)
        restartButton = findViewById(R.id.movieResume)
        progress = findViewById(R.id.movieProgress)

        titleLabel.text = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val cover = intent.getStringExtra(EXTRA_COVER) ?: ""
        // No placeholder: the JohnnyTV mark sits underneath and shows wherever
        // a poster is missing or will not load.
        if (cover.isNotBlank()) poster.load(cover) { crossfade(true) }

        // Nothing is known yet, so nothing is claimed.
        factsRow.visibility = View.GONE
        plotLabel.visibility = View.GONE
        castBlock.visibility = View.GONE
        resumeBlock.visibility = View.GONE

        watchButton.setOnClickListener { play(fromStart = false) }
        restartButton.setOnClickListener { play(fromStart = true) }
        favouriteButton.setOnClickListener {
            prefs.toggleFavourite(Kind.VOD, movieId)
            showFavourite()
        }
        showFavourite()
        // The remote lands on Watch, so OK plays without anyone going looking.
        watchButton.requestFocus()

        loadDetails()
    }

    override fun onResume() {
        super.onResume()
        showResume()
    }

    private fun showFavourite() {
        val isFavourite = prefs.isFavourite(Kind.VOD, movieId)
        favouriteButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (isFavourite) R.drawable.ic_star_filled else R.drawable.ic_star_outline, 0, 0, 0
        )
        favouriteButton.text = getString(
            if (isFavourite) R.string.in_favourites else R.string.add_to_favourites
        )
        favouriteButton.isActivated = isFavourite
    }

    /**
     * Part way through already? Then Watch means carry on, and a second button
     * offers the beginning. Nobody should have to scrub back to the start.
     */
    private fun showResume() {
        val position = prefs.position(Kind.VOD, movieId)
        val partWatched = position > 60_000L
        watchButton.setText(if (partWatched) R.string.resume else R.string.watch)
        restartButton.visibility = if (partWatched) View.VISIBLE else View.GONE

        // How far in, and how much is left - when the length is known. Without
        // it there is still something true to say: how long has been watched.
        if (!partWatched) {
            resumeBlock.visibility = View.GONE
            return
        }
        val watched = (position / 60_000L).toInt()
        val bar = findViewById<View>(R.id.movieBar)
        if (runtimeMinutes > watched) {
            val done = findViewById<View>(R.id.movieBarDone)
            val left = findViewById<View>(R.id.movieBarLeft)
            (done.layoutParams as LinearLayout.LayoutParams).weight = watched.toFloat()
            (left.layoutParams as LinearLayout.LayoutParams).weight = (runtimeMinutes - watched).toFloat()
            done.requestLayout()
            bar.visibility = View.VISIBLE
            resumeText.text = getString(
                R.string.watched_left, spanText(watched), spanText(runtimeMinutes - watched)
            )
        } else {
            bar.visibility = View.GONE
            resumeText.text = getString(R.string.watched_only, spanText(watched))
        }
        resumeBlock.visibility = View.VISIBLE
    }

    /** "42 min" or "1h 10m". */
    private fun spanText(minutes: Int): String =
        if (minutes >= 60) getString(R.string.hours_minutes, minutes / 60, minutes % 60)
        else getString(R.string.minutes_long, minutes)

    /** One fact, or one name, as a tag. */
    private fun tag(text: String, background: Int, color: Int, bold: Boolean): TextView {
        val density = resources.displayMetrics.density
        val label = TextView(this)
        label.text = text
        label.textSize = if (shortScreen) 12f else 15f
        label.setTextColor(color)
        if (bold) label.setTypeface(label.typeface, android.graphics.Typeface.BOLD)
        label.setBackgroundResource(background)
        label.maxLines = 1
        val side = (12 * density).toInt()
        val edge = (5 * density).toInt()
        label.setPadding(side, edge, side, edge)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.marginEnd = (8 * density).toInt()
        label.layoutParams = params
        return label
    }

    private fun loadDetails() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { prefs.client().movieInfo(movieId) }
            }
            progress.visibility = View.GONE
            // A portal that will not answer is not a reason to block the film -
            // the screen simply stays as bare as it started and Watch still works.
            result.onSuccess { show(it) }
        }
    }

    private fun show(info: MovieInfo) {
        if (info.containerExtension.isNotBlank()) containerExtension = info.containerExtension

        if (info.backdrop.isNotBlank()) {
            backdrop.load(info.backdrop) { crossfade(true) }
        }
        if (info.cover.isNotBlank()) poster.load(info.cover) { crossfade(true) }

        // The facts as tags: the rating first and in gold, then the year, the
        // length and up to three genres. Only the ones that actually exist.
        val light = getColor(R.color.text_primary)
        factsRow.removeAllViews()
        ratingText(info.rating)?.let {
            factsRow.addView(tag("\u2605 $it", R.drawable.bg_fact_rating, 0xFF14181D.toInt(), true))
        }
        info.releaseDate.take(4).takeIf { it.length == 4 && it.all { c -> c.isDigit() } }
            ?.let { factsRow.addView(tag(it, R.drawable.bg_fact_chip, light, false)) }
        runtimeMinutes = runtimeMinutes(info.duration)
        if (runtimeMinutes > 0) {
            factsRow.addView(tag(spanText(runtimeMinutes), R.drawable.bg_fact_chip, light, false))
        }
        info.genre.split(',', '/', '|').map { it.trim() }.filter { it.isNotBlank() }.take(3)
            .forEach { factsRow.addView(tag(it, R.drawable.bg_fact_chip, light, false)) }
        factsRow.visibility = if (factsRow.childCount == 0) View.GONE else View.VISIBLE

        // Five lines at most, so a long description cannot push the buttons
        // off the screen. Nothing sent is said plainly rather than left blank.
        if (info.plot.isBlank()) {
            plotLabel.setText(R.string.no_description)
            plotLabel.setTextColor(getColor(R.color.text_secondary))
        } else {
            plotLabel.text = info.plot
        }
        plotLabel.visibility = View.VISIBLE

        directorLabel.text =
            if (info.director.isBlank()) "" else getString(R.string.directed_by, info.director)
        directorLabel.visibility = if (info.director.isBlank()) View.GONE else View.VISIBLE

        // Each actor a tag of their own, as many as fit on one line.
        val names = info.cast.split(',').map { it.trim() }.filter { it.isNotBlank() }
        castRow.removeAllViews()
        castTitle.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
        castRow.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
        castBlock.visibility =
            if (shortScreen || (names.isEmpty() && info.director.isBlank())) View.GONE else View.VISIBLE
        if (names.isNotEmpty() && !shortScreen) {
            castRow.post {
                val room = castRow.width
                var used = 0
                for (name in names.take(8)) {
                    val pill = tag(name, R.drawable.bg_cast_pill, light, false)
                    pill.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                    val needs = pill.measuredWidth + (8 * resources.displayMetrics.density).toInt()
                    if (castRow.childCount > 0 && used + needs > room) break
                    castRow.addView(pill)
                    used += needs
                }
            }
        }

        showResume()
    }

    /** "8.4" out of ten, however the portal chose to write it. */
    private fun ratingText(raw: String): String? {
        val number = raw.trim().toDoubleOrNull() ?: return null
        if (number <= 0.0) return null
        // Some portals rate out of five, some out of ten. Anything at or under
        // five is taken at face value rather than doubled - claiming a 4.2 film
        // is an 8.4 would be worse than saying nothing.
        return String.format("%.1f", number)
    }

    /** The film's length in minutes, from whatever the portal wrote; 0 when it did not say. */
    private fun runtimeMinutes(raw: String): Int {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return 0
        // Already written as a clock: 01:52:30
        val clock = trimmed.split(":").mapNotNull { it.toIntOrNull() }
        val minutes = when {
            clock.size == 3 -> clock[0] * 60 + clock[1]
            clock.size == 2 -> clock[0] * 60 + clock[1]
            else -> trimmed.toIntOrNull()
        } ?: return 0
        return if (minutes <= 0) 0 else minutes
    }

    private fun play(fromStart: Boolean) {
        if (fromStart) prefs.savePosition(Kind.VOD, movieId, 0L, 0L)
        PlayerActivity.start(
            this,
            urls = prefs.client().movieUrls(movieId, containerExtension),
            title = titleLabel.text.toString(),
            kind = Kind.VOD,
            contentId = movieId
        )
    }

    companion object {
        const val EXTRA_MOVIE_ID = "movie_id"
        const val EXTRA_TITLE = "movie_title"
        const val EXTRA_COVER = "movie_cover"
        const val EXTRA_EXTENSION = "movie_ext"

        fun start(
            context: Context,
            movieId: String,
            title: String,
            cover: String,
            containerExtension: String
        ) {
            context.startActivity(
                Intent(context, MovieActivity::class.java)
                    .putExtra(EXTRA_MOVIE_ID, movieId)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_COVER, cover)
                    .putExtra(EXTRA_EXTENSION, containerExtension)
            )
        }
    }
}
