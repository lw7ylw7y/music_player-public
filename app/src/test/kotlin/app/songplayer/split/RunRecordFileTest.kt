package app.songplayer.split

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunRecordFileTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun RunRecordFile_ReplacesAtomically_HidesTemporaryFiles_AndDeletes() {
        val dir = File(temp.root, "split-runs")                 // does not exist yet: the first write makes it
        val files = RunRecordFile(dir)
        assertNull(files.read("a.json"))
        files.writeAtomic("a.json", "one".toByteArray())
        files.writeAtomic("a.json", "two".toByteArray())
        assertArrayEquals("two".toByteArray(), files.read("a.json"))
        File(dir, "b.json.tmp").writeText("half")               // a write that was killed before the rename
        assertEquals(listOf("a.json"), files.names())
        assertNull(files.read("b.json"))
        files.delete("a.json")
        assertEquals(emptyList<String>(), files.names())
    }
}
