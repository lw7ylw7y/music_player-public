package app.songplayer.details

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.net.toUri

/**
 * How the app gets permission to change the user's song files (R-27, Technical Design 5.8). The first way is the special
 * "Media management" access, granted once in Android's settings; the fallback is a system dialog for a batch of songs, which
 * only an activity on screen can show. The screens use these two helpers (Stories H3 and H8).
 */
object MediaManagement {
    /** True when the user has granted the special access to this app. */
    fun isGranted(context: Context): Boolean = MediaStore.canManageMedia(context)

    /** Opens Android's "Media management apps" page for this app, where the user turns the access on. */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).setData("package:${context.packageName}".toUri())

    /** The system dialog that asks for write access to [uris]; the result comes back to the activity that starts it. */
    fun writeRequest(context: Context, uris: Collection<Uri>): PendingIntent = MediaStore.createWriteRequest(context.contentResolver, uris)
}
