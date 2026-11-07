package app.songplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.songplayer.core.library.Song
import app.songplayer.library.CoverRequest
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * The cover of a song. The placeholder (the artist's first character, or a note when there is no artist)
 * is always drawn underneath, so a song without art, or one still loading, shows it with no extra state.
 * The caller sets the size; [sizePx] is the thumbnail size to ask Android for.
 */
@Composable
fun Cover(song: Song, sizePx: Int, modifier: Modifier = Modifier) = Cover(song.mediaStoreId, song.artist, sizePx, modifier)

/** The same cover from the two facts it needs: the media-index id, and the artist tag (null when there is none). */
@Composable
fun Cover(mediaStoreId: Long, artist: String?, sizePx: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(mediaStoreId, sizePx) {
        ImageRequest.Builder(context).data(CoverRequest(mediaStoreId, sizePx)).build()
    }
    Box(
        modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(placeholderCharacter(artist), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // A file opened from another app has no media-index id (-1) and so no cover to ask for.
        if (mediaStoreId >= 0) {
            AsyncImage(model = request, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/** The first character (a whole code point, so a rare Chinese character is not cut in half) or a note. */
internal fun placeholderCharacter(artist: String?): String {
    val first = artist?.trim()?.takeIf { it.isNotEmpty() } ?: return "♪"
    return String(Character.toChars(first.codePointAt(0))).uppercase()
}
