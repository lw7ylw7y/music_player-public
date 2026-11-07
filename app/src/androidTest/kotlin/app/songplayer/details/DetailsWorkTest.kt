package app.songplayer.details

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L3: the unique background work on the real WorkManager. The work is queued a day ahead so it never runs during the test; what is
 * checked is what was queued (Stories H7). The rules of a run are tested on the JVM (`DetailsRunnerTest`).
 */
@RunWith(AndroidJUnit4::class)
class DetailsWorkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val day = 24L * 60 * 60 * 1000

    private fun infos(): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(DetailsWork.UNIQUE_NAME).get()
        .filter { !it.state.isFinished }

    @Before @After
    fun clean() = DetailsWork.cancel(context)

    @Test
    fun DetailsWork_WifiOnly_UsesUnmeteredConstraint() {
        DetailsWork.enqueue(context, wifiOnly = true, delayMs = day)
        val info = infos().single()
        assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
        assertTrue(info.constraints.requiresBatteryNotLow())
        // Changing the option starts over with the new constraint.
        DetailsWork.enqueue(context, wifiOnly = false, delayMs = day, policy = ExistingWorkPolicy.REPLACE)
        assertEquals(NetworkType.CONNECTED, infos().single().constraints.requiredNetworkType)
    }

    @Test
    fun DetailsWork_AppStart_EnqueuesOnce() {
        repeat(3) { DetailsWork.enqueue(context, wifiOnly = true, delayMs = day) }
        assertEquals(1, infos().size)
    }

    @Test
    fun DetailsWork_TurnOff_CancelsTheWork() {
        DetailsWork.enqueue(context, wifiOnly = true, delayMs = day)
        assertEquals(1, infos().size)
        DetailsWork.cancel(context)
        assertEquals(0, infos().size)
    }
}
