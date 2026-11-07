package app.songplayer.ui

import app.songplayer.core.split.SplitEntry
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.songplayer.R
import app.songplayer.core.library.Song
import app.songplayer.core.text.AlbumGroup
import app.songplayer.core.text.ArtistGroup

private val ListCover = 48.dp
private const val TILE_COVER_PX = 400

/**
 * Title, artist and album of one song (UX Design A3). Titles take one line with an ellipsis; rows grow with the font.
 * A tap plays the song. The menu (three dots) offers Play next and Add to queue when the caller supplies them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    onClick: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    /** Long-press starts selecting several songs (UX Design E5); TalkBack gets the same as the action "Select". */
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    cantPlay: Boolean = false,
) {
    val editDetails = LocalEditDetails.current
    val split = LocalSplitSong.current
    val deleteSongs = LocalDeleteSongs.current
    val described = stringResource(R.string.song_row_description, song.displayTitle, song.displayArtist, song.displayAlbum)
    val spoken = when {
        selected -> stringResource(R.string.song_row_selected, described)
        cantPlay -> stringResource(R.string.song_row_cant_play, described)
        playing -> stringResource(R.string.song_row_playing, described)
        else -> described
    }
    val coverPx = with(LocalDensity.current) { ListCover.roundToPx() }
    val selectLabel = stringResource(R.string.select_action)
    val tap = if (onClick != null) {
        Modifier.combinedClickable(
            onClickLabel = stringResource(R.string.play),
            onLongClickLabel = if (onLongClick != null) selectLabel else null,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    Row(
        modifier.fillMaxWidth().then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier).heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The cover and the texts are one node for a screen reader; the menu button beside them is its own.
        Row(
            // The long-press action keeps its label "Select" for TalkBack; the click action is outside the cleared semantics.
            Modifier.weight(1f).then(tap).clearAndSetSemantics { contentDescription = spoken },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // A selected row shows a check mark in place of the cover as well as the tinted background (never color alone).
            if (selected) {
                Box(Modifier.size(ListCover), contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, contentDescription = null) }
            } else {
                Cover(song, coverPx, Modifier.size(ListCover))
            }
            Column(Modifier.weight(1f)) {
                // The playing song is marked by a bold title and by its spoken text ("playing"), not by color (UX Design 3).
                Text(
                    song.displayTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (playing) FontWeight.ExtraBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (cantPlay) {
                    CantPlayMark()
                } else {
                    Text(
                        stringResource(R.string.song_row_subtitle, song.displayArtist, song.displayAlbum),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (onPlayNext != null || onAddToQueue != null || onRemoveFromPlaylist != null || onAddToPlaylist != null) {
            SongMenu(
                song.displayTitle, onPlayNext, onAddToQueue, onAddToPlaylist, onRemoveFromPlaylist, editDetails?.let { edit -> { edit(song) } },
                split?.takeIf { SplitEntry.offered(song) }?.let { start -> { start(song) } },
                deleteSongs?.let { delete -> { delete(listOf(song)) } },
            )
        }
    }
}

/** "Can't play" with a block icon: the mark of a song that failed to play, in place of its artist line (UX Design A11). */
@Composable
fun CantPlayMark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // The icon grows with the font size, like the word beside it.
        val iconSize = with(LocalDensity.current) { 16.sp.toDp() }
        Icon(PlayerIcons.Block, contentDescription = null, modifier = Modifier.size(iconSize), tint = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.cant_play), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

/** The three-dot menu of a song row (UX Design E1); "Add to playlist" joins it with the playlist screens. */
@Composable
private fun SongMenu(
    title: String,
    onPlayNext: (() -> Unit)?,
    onAddToQueue: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onRemoveFromPlaylist: (() -> Unit)?,
    onEditDetails: (() -> Unit)?,
    onSplit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.song_more_options, title))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (onPlayNext != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_play_next)) },
                    onClick = {
                        open = false
                        onPlayNext()
                    },
                )
            }
            if (onAddToQueue != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_add_to_queue)) },
                    onClick = {
                        open = false
                        onAddToQueue()
                    },
                )
            }
            if (onAddToPlaylist != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_add_to_playlist)) },
                    onClick = {
                        open = false
                        onAddToPlaylist()
                    },
                )
            }
            if (onRemoveFromPlaylist != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_remove_from_playlist)) },
                    onClick = {
                        open = false
                        onRemoveFromPlaylist()
                    },
                )
            }
            if (onEditDetails != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_edit_details)) },
                    onClick = {
                        open = false
                        onEditDetails()
                    },
                )
            }
            if (onSplit != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_split)) },
                    onClick = {
                        open = false
                        onSplit()
                    },
                )
            }
            if (onDelete != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_delete_song), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        open = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/**
 * "Edit details" on the song menu (R-30). The root provides it, so every list that shows a song menu offers it without each screen
 * passing it along; a row outside the root (a test, a preview) has no such row in its menu.
 */
val LocalEditDetails = androidx.compose.runtime.staticCompositionLocalOf<((Song) -> Unit)?> { null }

/**
 * "Split into songs" on the song menu (Requirements R-33). The root provides it; the row offers it only for an MP3 of at least ten minutes
 * ([SplitEntry.offered]). A row outside the root has no such entry.
 */
val LocalSplitSong = androidx.compose.runtime.staticCompositionLocalOf<((Song) -> Unit)?> { null }

/**
 * "Delete from phone" on the song menu and in the selection bar (R-50). The root provides it and asks the question; a row outside the
 * root has no such entry.
 */
val LocalDeleteSongs = androidx.compose.runtime.staticCompositionLocalOf<((List<Song>) -> Unit)?> { null }

/** One tile of the two-column Albums grid (UX Design A6). */
@Composable
fun AlbumTile(album: AlbumGroup, modifier: Modifier = Modifier) {
    val count = countText(R.plurals.song_count, album.songCount)
    val spoken = stringResource(R.string.album_row_description, album.displayName, album.artistLine, count)
    Column(modifier.padding(8.dp).clearAndSetSemantics { contentDescription = spoken }) {
        Cover(album.cover, TILE_COVER_PX, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(album.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(album.artistLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(count, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** An album as a row, for search results (UX Design B1): cover, name, artist line and song count. */
@Composable
fun AlbumRow(album: AlbumGroup, modifier: Modifier = Modifier) {
    val count = countText(R.plurals.song_count, album.songCount)
    val spoken = stringResource(R.string.album_row_description, album.displayName, album.artistLine, count)
    val coverPx = with(LocalDensity.current) { ListCover.roundToPx() }
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(album.cover, coverPx, Modifier.size(ListCover))
        Column(Modifier.weight(1f)) {
            Text(album.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.album_row_subtitle, album.artistLine, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One row of the Artists tab (UX Design A7). */
@Composable
fun ArtistRow(artist: ArtistGroup, modifier: Modifier = Modifier) {
    val count = countText(R.plurals.song_count, artist.songCount)
    val spoken = stringResource(R.string.artist_row_description, artist.displayName, count)
    Column(
        modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.Center,
    ) {
        Text(artist.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(count, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
