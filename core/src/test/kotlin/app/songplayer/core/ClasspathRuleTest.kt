package app.songplayer.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClasspathRuleTest {
    @Test
    fun Core_BuildClasspath_HasNoAndroidArtifacts() {
        val entries = System.getProperty("java.class.path").split(File.pathSeparator)
        assertTrue("class path is empty", entries.isNotEmpty())
        // Gradle cache paths contain the group (".../androidx.annotation/annotation-jvm/..."), and the
        // Android SDK jar is android.jar, so match on the whole path, not only the file name.
        val offending = entries.filter {
            val path = it.replace('\\', '/').lowercase()
            File(it).name.lowercase() == "android.jar" ||
                listOf("/androidx.", "/com.google.android", "/com.android.").any(path::contains)
        }
        assertTrue("core must not depend on Android: $offending", offending.isEmpty())
    }
}
