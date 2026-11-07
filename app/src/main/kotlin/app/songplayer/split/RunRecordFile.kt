package app.songplayer.split

import app.songplayer.core.split.AtomicFiles
import java.io.File
import java.io.FileOutputStream

/**
 * The run records on the app's private storage, `split-runs/<id>.json` (Technical Design 5.3). A write goes to a temporary file, is flushed to
 * disk, and is then renamed over the record, so a reader sees the old record or the new one and never half of one. [names] never lists a
 * temporary file.
 */
class RunRecordFile(private val dir: File) : AtomicFiles {
    override fun writeAtomic(name: String, bytes: ByteArray) {
        check(dir.isDirectory || dir.mkdirs()) { "cannot make the run folder" }
        val tmp = File(dir, "$name.tmp")
        FileOutputStream(tmp).use { out -> out.write(bytes); out.fd.sync() }
        if (!tmp.renameTo(File(dir, name))) throw java.io.IOException("cannot save the run record")
    }

    override fun read(name: String): ByteArray? = File(dir, name).takeIf { it.isFile }?.readBytes()

    override fun delete(name: String) { File(dir, name).delete() }

    override fun names(): List<String> = (dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") } ?: emptyArray()).map { it.name }
}
