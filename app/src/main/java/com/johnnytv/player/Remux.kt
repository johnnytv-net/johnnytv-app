package com.johnnytv.player

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * TURNING A RECORDING INTO A PROPER VIDEO FILE.
 *
 * What the recorder writes is a transport stream: the format television is
 * broadcast in, designed to be joined halfway through and to survive losing
 * chunks of itself. Excellent for recording, poor for watching. It carries no
 * index, so a player cannot know how long it is or where anything sits - it
 * guesses from the average rate, and on a recording stitched from thousands of
 * pieces the guess is wildly wrong. Ask for two minutes in and you land at
 * fourteen; ask the decoder to do it often enough and the whole app goes down.
 *
 * An MP4 has an index. The same pictures and the same sound, written once at
 * the end into a container that says exactly where everything is.
 *
 * Nothing is re-encoded here - the frames are copied across untouched, which
 * is why this costs minutes rather than hours and loses no quality at all.
 *
 * Not everything can make the trip: MP4 will not hold some broadcast audio
 * formats. When that happens this says so plainly and the recording is left as
 * it was, which still plays - rather than quietly producing a film with no
 * sound.
 */
object Remux {

    const val MP4_NAME = "whole.mp4"

    /** What happened, in words fit to show somebody. */
    data class Result(val ok: Boolean, val message: String)

    fun toMp4(source: File, into: File): Result {
        if (!source.exists() || source.length() < 1_000_000L) {
            return Result(false, "There is no joined file to convert yet.")
        }

        val building = File(into.parentFile, "whole.building.mp4")
        runCatching { building.delete() }

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(source.absolutePath)

            // Work out what is in there, and whether MP4 will take it.
            var videoTrack = -1
            var audioTrack = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null
            var audioKind = ""

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (videoTrack < 0 && mime.startsWith("video/")) {
                    videoTrack = i
                    videoFormat = format
                } else if (audioTrack < 0 && mime.startsWith("audio/")) {
                    audioKind = mime
                    if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                        audioTrack = i
                        audioFormat = format
                    }
                }
            }

            if (videoTrack < 0 || videoFormat == null) {
                return Result(false, "No video could be found in the recording.")
            }
            if (audioTrack < 0) {
                return Result(
                    false,
                    "The sound in this recording is " + (audioKind.ifBlank { "in a format" }) +
                        ", which an MP4 cannot hold. The recording has been left as it is " +
                        "and still plays."
                )
            }

            muxer = MediaMuxer(building.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideo = muxer.addTrack(videoFormat)
            val outAudio = muxer.addTrack(audioFormat!!)
            muxer.start()

            val buffer = ByteBuffer.allocate(1 shl 21)
            val info = MediaCodec.BufferInfo()
            var copiedVideo = 0L
            var copiedAudio = 0L

            for ((inTrack, outTrack) in listOf(videoTrack to outVideo, audioTrack to outAudio)) {
                extractor.selectTrack(inTrack)
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    // A timestamp that goes backwards - which a rejoined
                    // recording is full of - is dropped rather than written,
                    // because a muxer refuses the whole file over one.
                    if (info.presentationTimeUs >= 0) {
                        runCatching { muxer.writeSampleData(outTrack, buffer, info) }
                        if (inTrack == videoTrack) copiedVideo++ else copiedAudio++
                    }
                    extractor.advance()
                }
                extractor.unselectTrack(inTrack)
            }

            muxer.stop()
            muxer.release()
            muxer = null
            extractor.release()
            extractor = null

            if (building.length() < 1_000_000L || copiedVideo == 0L) {
                runCatching { building.delete() }
                return Result(false, "The conversion produced nothing usable.")
            }

            runCatching { into.delete() }
            building.renameTo(into)
            return Result(
                true,
                "Converted to a proper video file: " + (into.length() / (1024 * 1024)) +
                    " MB, " + copiedVideo + " pictures and " + copiedAudio + " pieces of sound. " +
                    "Press play as normal."
            )
        } catch (e: Exception) {
            return Result(false, "Could not convert it: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
        }
    }
}
