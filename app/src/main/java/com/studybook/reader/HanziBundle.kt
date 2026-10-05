package com.studybook.reader

import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Inflater

/**
 * Stroke data for every character, packed into one file by tools/build_assets.py:
 * "HZB1", u32 count, count x (u32 codepoint, u32 offset, u32 length) sorted by codepoint, then zlib-compressed JSON.
 * Only the index is held in memory; each character is read and unpacked when asked for.
 */
class HanziBundle(
    private val channel: FileChannel,
    private val base: Long,
    /** Keeps the asset's file descriptor open for as long as the bundle is used. */
    @Suppress("unused") private val owner: Any? = null,
) {
    private val codepoints: IntArray
    private val offsets: IntArray
    private val lengths: IntArray

    init {
        val head = read(0, 8)
        require(String(head.array(), 0, 4, Charsets.US_ASCII) == "HZB1") { "not a stroke data bundle" }
        val count = head.getInt(4)
        val index = read(8, count * 12)
        codepoints = IntArray(count)
        offsets = IntArray(count)
        lengths = IntArray(count)
        for (i in 0 until count) {
            codepoints[i] = index.getInt(i * 12)
            offsets[i] = index.getInt(i * 12 + 4)
            lengths[i] = index.getInt(i * 12 + 8)
        }
    }

    val size: Int get() = codepoints.size

    /** The character's stroke data JSON, or null when the bundle doesn't have it. */
    fun json(codepoint: Int): ByteArray? {
        val i = codepoints.binarySearch(codepoint)
        if (i < 0) return null
        val packed = read(offsets[i].toLong(), lengths[i]).array()
        val inflater = Inflater()
        try {
            inflater.setInput(packed)
            val out = ByteArrayOutputStream(packed.size * 4)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    /** Positional reads are safe from several threads at once. */
    private fun read(position: Long, length: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        var at = base + position
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, at)
            if (n < 0) break
            at += n
        }
        buffer.flip()
        return buffer
    }

    companion object {
        const val ASSET = "hanzi.bin"

        /** Opens the bundle from the app's assets; it is stored uncompressed in the APK so it can be read in place. */
        fun open(context: android.content.Context): HanziBundle {
            val fd = context.assets.openFd(ASSET)
            return HanziBundle(FileInputStream(fd.fileDescriptor).channel, fd.startOffset, fd)
        }
    }
}
