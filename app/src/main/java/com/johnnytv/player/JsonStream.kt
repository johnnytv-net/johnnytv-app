package com.johnnytv.player

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader

/*
 * A LONG LIST, ONE ITEM AT A TIME.
 *
 * The channel, film and series lists used to be read the simple way: the whole
 * answer as one piece of text, then the whole of it again as a tree of objects,
 * and only then picked through for the handful of fields the app keeps. For a
 * film list that is tens of megabytes held three times over, for a moment, on
 * a box whose whole spare memory is a couple of hundred - and it happened on
 * every Refresh and, for the saved copy, on every start.
 *
 * This reads the same list as it arrives and hands over one item at a time.
 * Each item is still an ordinary JSONObject, built the way the old reader
 * built it, so everything that picks fields out of one is unchanged; there is
 * simply never more than one of them in memory.
 */
object JsonStream {

    /** The text was not a list at all - an error page, say, or nothing. */
    class NotAList : Exception("not a list")

    /**
     * Calls [each] for every object in the list [source] holds.
     *
     * Returns false, having called nothing, when the text is a single object
     * rather than a list - which is how a portal says "no such thing" - and
     * throws [NotAList] when it is neither. A list that breaks off part-way
     * throws rather than passing for a short one.
     */
    fun eachObject(source: Reader, each: (JSONObject) -> Unit): Boolean {
        val reader = JsonReader(source)
        reader.isLenient = true
        val first = try {
            reader.peek()
        } catch (e: java.io.EOFException) {
            throw NotAList()
        } catch (e: android.util.MalformedJsonException) {
            throw NotAList()
        }
        return when (first) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        each(readObject(reader))
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endArray()
                true
            }
            JsonToken.BEGIN_OBJECT -> false
            else -> throw NotAList()
        }
    }

    private fun readObject(reader: JsonReader): JSONObject {
        val out = JSONObject()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            out.put(name, readValue(reader))
        }
        reader.endObject()
        return out
    }

    private fun readValue(reader: JsonReader): Any = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> readObject(reader)
        JsonToken.BEGIN_ARRAY -> {
            val array = JSONArray()
            reader.beginArray()
            while (reader.hasNext()) array.put(readValue(reader))
            reader.endArray()
            array
        }
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> number(reader.nextString())
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> {
            reader.nextNull()
            JSONObject.NULL
        }
        else -> {
            reader.skipValue()
            JSONObject.NULL
        }
    }

    /** A whole number where it is one, as the old reader gave; otherwise as written. */
    private fun number(text: String): Any {
        text.toLongOrNull()?.let { whole ->
            return if (whole >= Int.MIN_VALUE && whole <= Int.MAX_VALUE) whole.toInt() else whole
        }
        val real = text.toDoubleOrNull()
        return if (real != null && !real.isNaN() && !real.isInfinite()) real else text
    }
}
