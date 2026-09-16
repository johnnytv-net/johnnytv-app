package com.johnnytv.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
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
    private lateinit var metaLabel: TextView
    private lateinit var ratingLabel: TextView
    private lateinit var plotLabel: TextView
    private lateinit var castLabel: TextView
    private lateinit var directorLabel: TextView
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
        metaLabel = findViewById(R.id.movieMeta)
        ratingLabel = findViewById(R.id.movieRating)
        plotLabel = findViewById(R.id.moviePlot)
        castLabel = findViewById(R.id.movieCast)
        directorLabel = findViewById(R.id.movieDirector)
        watchButton = findViewById(R.id.movieWatch)
        favouriteButton = findViewById(R.id.movieFavourite)
        restartButton = findViewById(R.id.movieResume)
        progress = findViewById(R.id.movieProgress)

        titleLabel.text = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val cover = intent.getStringExtra(EXTRA_COVER) ?: ""
        poster.load(cover.ifBlank { null }) {
            crossfade(true)
            placeholder(R.drawable.tile_placeholder)
            error(R.drawable.tile_placeholder)
            fallback(R.drawable.tile_placeholder)
        }

        // Nothing is known yet, so nothing is claimed.
        metaLabel.visibility = View.GONE
        plotLabel.visibility = View.GONE
        castLabel.visibility = View.GONE
        directorLabel.visibility = View.GONE

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
        if (info.cover.isNotBlank()) {
            poster.load(info.cover) {
                crossfade(true)
                placeholder(R.drawable.tile_placeholder)
                error(R.drawable.tile_placeholder)
            }
        }

        // Year, genre and running time on one line, with dots only between the
        // parts that actually exist.
        val bits = ArrayList<String>(3)
        info.releaseDate.take(4).takeIf { it.length == 4 && it.all { c -> c.isDigit() } }
            ?.let { bits.add(it) }
        info.genre.takeIf { it.isNotBlank() }?.let { bits.add(it) }
        runtimeText(info.duration)?.let { bits.add(it) }
        metaLabel.text = bits.joinToString("   ·   ")
        metaLabel.visibility = if (bits.isEmpty()) View.GONE else View.VISIBLE

        val rating = ratingText(info.rating)
        ratingLabel.text = rating ?: ""
        ratingLabel.setCompoundDrawablesRelativeWithIntrinsicBounds(
            R.drawable.ic_star_filled, 0, 0, 0
        )
        ratingLabel.visibility = if (rating == null) View.GONE else View.VISIBLE

        plotLabel.text = info.plot
        plotLabel.visibility = if (info.plot.isBlank()) View.GONE else View.VISIBLE

        castLabel.text = if (info.cast.isBlank()) "" else getString(R.string.cast_line, info.cast)
        castLabel.visibility = if (info.cast.isBlank()) View.GONE else View.VISIBLE

        directorLabel.text =
            if (info.director.isBlank()) "" else getString(R.string.director_line, info.director)
        directorLabel.visibility = if (info.director.isBlank()) View.GONE else View.VISIBLE
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

    /** Minutes or "1h 52m", from whatever the portal wrote. */
    private fun runtimeText(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        // Already written as a clock: 01:52:30
        val clock = trimmed.split(":").mapNotNull { it.toIntOrNull() }
        val minutes = when {
            clock.size == 3 -> clock[0] * 60 + clock[1]
            clock.size == 2 -> clock[0] * 60 + clock[1]
            else -> trimmed.toIntOrNull()
        } ?: return null
        if (minutes <= 0) return null
        return if (minutes >= 60) {
            getString(R.string.hours_minutes, minutes / 60, minutes % 60)
        } else {
            getString(R.string.minutes_long, minutes)
        }
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
