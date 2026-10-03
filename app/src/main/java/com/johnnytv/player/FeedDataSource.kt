package com.johnnytv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Hands a player whatever a [LineFeed] has stitched together.
 *
 * To the player this is one long unbroken stream. The reconnects, and the
 * finding of the exact packet to carry on from, all happen behind it - which
 * is the point: a feed that drops and comes back is not something the player
 * ever has to hear about.
 */
@OptIn(UnstableApi::class)
class FeedDataSource(private val feed: LineFeed, private val address: Uri) : BaseDataSource(true) {

    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val count = try {
            feed.pipe.read(buffer, offset, length)
        } catch (interrupted: InterruptedException) {
            throw InterruptedIOException()
        }
        if (count < 0) {
            // The channel never opened at this address. An error, not an
            // ending, so the player moves on to the next address it has.
            if (feed.gaveUp) throw IOException("Nothing arrived from this address.")
            return C.RESULT_END_OF_INPUT
        }
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = address

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
