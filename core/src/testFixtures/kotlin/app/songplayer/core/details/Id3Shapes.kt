package app.songplayer.core.details

/** Generated MP3 bytes with every tag shape the writer meets (Stories H2). No audio or private data is checked in. */
object Id3Shapes {
    /** Pseudo-random "audio" that starts with an MPEG frame sync, so it looks like the start of an MP3 to a sniffer. */
    fun audio(size: Int = 3000, seed: Long = 7): ByteArray {
        val r = java.util.Random(seed)
        return ByteArray(size).also { r.nextBytes(it); it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte() }
    }

    /** An ID3v1 tag: 128 bytes at the very end of a file. */
    fun id3v1(title: String = "old v1 title"): ByteArray {
        val b = ByteArray(128)
        "TAG".toByteArray().copyInto(b, 0)
        title.toByteArray(Charsets.ISO_8859_1).copyInto(b, 3)
        return b
    }

    fun text(major: Int, value: String): ByteArray = Id3.encodeText(major, value)

    /** One frame; [flagBytes] are written as they are. [size] defaults to the data length. */
    fun frame(major: Int, id: String, data: ByteArray, flagBytes: Int = 0, size: Int = data.size): ByteArray {
        val out = ByteArray(10 + data.size)
        id.toByteArray(Charsets.ISO_8859_1).copyInto(out, 0)
        if (major == 4) for (i in 0..3) out[4 + i] = ((size shr (7 * (3 - i))) and 0x7F).toByte()
        else for (i in 0..3) out[4 + i] = ((size shr (8 * (3 - i))) and 0xFF).toByte()
        out[8] = (flagBytes shr 8).toByte(); out[9] = flagBytes.toByte()
        data.copyInto(out, 10)
        return out
    }

    fun textFrame(major: Int, id: String, value: String, flagBytes: Int = 0): ByteArray = frame(major, id, text(major, value), flagBytes)

    /** A whole tag: header, [frames], [padding] zero bytes. [headerFlags] are written as they are. */
    fun tag(major: Int, frames: List<ByteArray>, padding: Int = 0, headerFlags: Int = 0, extra: ByteArray = ByteArray(0)): ByteArray {
        val body = frames.sumOf { it.size } + padding + extra.size
        val out = ByteArray(10 + body)
        "ID3".toByteArray().copyInto(out, 0)
        out[3] = major.toByte(); out[5] = headerFlags.toByte()
        for (i in 0..3) out[6 + i] = ((body shr (7 * (3 - i))) and 0x7F).toByte()
        var pos = 10
        for (f in frames) { f.copyInto(out, pos); pos += f.size }
        extra.copyInto(out, pos)
        return out
    }

    class Shape(val name: String, val file: ByteArray, val supported: Boolean = true)

    /** Every shape: the first group is written, the second (supported = false) is refused. */
    fun shapes(): List<Shape> {
        val a = audio()
        val v1 = id3v1()
        fun t3(vararg frames: ByteArray, padding: Int = 0) = tag(3, frames.toList(), padding)
        fun t4(vararg frames: ByteArray, padding: Int = 0) = tag(4, frames.toList(), padding)
        return listOf(
            Shape("no tag", a),
            Shape("no tag with an ID3v1 tail", a + v1),
            Shape("2.3 small tag, no padding", t3(textFrame(3, "TIT2", "old"), textFrame(3, "TPE1", "old artist")) + a),
            Shape("2.3 with big padding", t3(textFrame(3, "TIT2", "old"), padding = 4000) + a),
            Shape("2.3 with a cover frame", t3(textFrame(3, "TIT2", "old"), frame(3, "APIC", ByteArray(500) { it.toByte() }), padding = 10) + a),
            Shape("2.4 with big padding", t4(textFrame(4, "TIT2", "old"), textFrame(4, "TALB", "old album"), padding = 3000) + a),
            Shape("2.4 no padding, UTF-8 text", t4(textFrame(4, "TIT2", "我等你"), textFrame(4, "TPE1", "張學友")) + a),
            Shape("2.3 UTF-16 Chinese text", t3(textFrame(3, "TIT2", "後來"), textFrame(3, "TPE1", "劉若英"), padding = 50) + a),
            Shape("2.3 duplicate title frames", t3(textFrame(3, "TIT2", "one"), textFrame(3, "TIT2", "two"), padding = 100) + a),
            Shape("2.3 with an ID3v1 tail", t3(textFrame(3, "TIT2", "old"), padding = 100) + a + v1),
            Shape("2.3 empty tag", t3(padding = 20) + a),
            Shape("2.4 per-frame flags on another frame", t4(frame(4, "TXXX", "x\u0000y".toByteArray(), flagBytes = 0x0003), textFrame(4, "TIT2", "old"), padding = 80) + a),
        )
    }

    fun refused(): List<Shape> {
        val a = audio()
        return listOf(
            Shape("2.2 tag", "ID3".toByteArray() + byteArrayOf(2, 0, 0) + byteArrayOf(0, 0, 0, 30) + ByteArray(30) + a, supported = false),
            Shape("tag-wide unsynchronisation flag", tag(3, listOf(textFrame(3, "TIT2", "old")), padding = 20, headerFlags = 0x80) + a, supported = false),
            Shape("extended header flag", tag(3, listOf(textFrame(3, "TIT2", "old")), padding = 20, headerFlags = 0x40) + a, supported = false),
            Shape("2.4 footer flag", tag(4, listOf(textFrame(4, "TIT2", "old")), padding = 20, headerFlags = 0x10) + a, supported = false),
            Shape("2.3 compressed title frame", tag(3, listOf(textFrame(3, "TIT2", "old", flagBytes = 0x0080)), padding = 20) + a, supported = false),
            Shape("2.4 encrypted title frame", tag(4, listOf(textFrame(4, "TIT2", "old", flagBytes = 0x0004)), padding = 20) + a, supported = false),
            Shape("a frame larger than the tag", tag(3, listOf(frame(3, "TIT2", ByteArray(10), size = 5000)), padding = 0) + a, supported = false),
        )
    }
}
