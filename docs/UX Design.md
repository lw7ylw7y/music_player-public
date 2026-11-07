# UX Design: Music Player (music player)

| | |
|---|---|
| Status | Agreed on 2026-10-25 (the user: "Mockups look good"). The proposals listed at the end of section 8 stand unless the user strikes them. |
| Last updated | 2026-11-06 (4.11: the splitter, merged from its own folder) |
| Platform | Android 13 and newer, phones only, Material 3 conventions, Android Auto |
| Follows | docs/Requirements.md (R-1 to R-18, R-21, R-22, R-24; NFR-5, NFR-6, NFR-8); the V1.1 items R-19 and R-27 to R-31 are designed in 4.10 (Draft, 2026-11-03); the splitter R-33 to R-49 is designed in 4.11 (V2); R-23 is not designed here |
| Mockups | docs/mockups/index.html (static HTML fixtures; open in any browser). Screen codes below (A1, D3 ...) are the mockup figure ids. |
| Covers | Screen map, style guide, and one spec per screen for every scenario in S-1 to S-6, and 4.11 for S-8 to S-10 |

**What this is not:** UI code. Implementation questions (Compose, which player library, how the widget and Android Auto are built) go to the technical design.

**Mockups are fixtures.** They are hand-generated HTML with invented songs and artists, not screenshots of a built app. I rendered them myself in Microsoft Edge on the user's Windows PC (headless screenshots that I looked at) to check layout and wording; nobody else has reviewed those renders. Light and dark are drawn only for A3/A4, D1/D2, G1 and G2, and large text only for A10. They were not rendered on an Android phone or emulator. Real system bars, the keyboard, TalkBack, the widget picker and Android Auto will differ; Android Auto and the notification are drawn by Android, so those two mockups are illustrations only.

## 1. Review of the requirements (UX view)

The requirements settle these, and this design follows them: calm and minimal look; light or dark follows the phone; the app opens on the Library only; a song that cannot play is skipped silently and marked "Can't play"; a refused permission explains and offers "Try again" and "Open Settings"; new songs are shown by a Recently added sort plus a one-time message; MP3 only; English text first.

Gaps and tensions found, each with the proposal this design makes (the user confirms or changes):

| # | Finding | Proposal |
|---|---|---|
| 1 | "Play" and "Shuffle" buttons on albums and playlists need a rule next to the global shuffle toggle (R-7). | The user's decision: a playlist and the current queue are different things. "Play" copies the list into the queue and plays it with the current shuffle setting; it never changes that setting. "Shuffle" copies the list into the queue and turns shuffle on. Shuffling, "Play next" and "Add to queue" change only the queue, never the playlist (D-14). |
| 2 | R-10 needs reordering playlists, but dragging is hard for fingers and impossible for TalkBack users. | Drag handles plus a ⋮ menu on each row with "Move up", "Move down", "Move to top" and "Remove from playlist" (decision D-3; mockups C3, C5). |
| 3 | R-12 says the mark is set after a failed attempt, but a deleted file leaves the library, so it can only show on playlist entries, the queue and damaged files. | The design shows the mark wherever a failed song is listed and says nothing more (A11, C2). |
| 4 | Sorting Chinese titles "by Title" needs an order (Unicode, pinyin, strokes). | The user's decision: Hanyu Pinyin order, the standard in mainland China, with Latin-letter titles mixed in alphabetically (requirement R-25). Search is not pinyin-based (decided earlier). |
| 5 | R-16 (Android Auto) says "browse", but Android Auto limits list depth and draws its own screens. | The app supplies a four-branch browse tree and metadata; Android draws the screens (G3). Details are for the technical design. |
| 6 | Seeking, next and previous must work from the notification and the car, but the sleep timer and queue are phone-only. | Phone-only features are not offered on Android Auto; the sleep timer stays on the phone (R-14 does not require it in the car). |
| 7 | 2,000 songs in one list is long to scroll. | Search is one tap from the Library, and the user chose a fast-scroll handle that shows the pinyin or Latin initial letter while dragging (R-25). |

## 2. Screen map

One activity; Back always goes one step back. Now Playing slides up from the mini player and Back closes it.

```text
 (launch) ──▶ first start? ──▶ A1 Find the music ──▶ Android permission dialog
                  │                                          │
                  │ allowed                                   │ refused ─▶ A2 Music access is off
                  ▼                                          │             (Try again / Open Settings)
        ┌───────────────────────────────┐ ◀──────────────────┘
        │ LIBRARY (home)                │── 🔍 ──▶ B1 Search ─▶ results ─▶ play / detail
        │ tabs: Songs Albums Artists    │── ⋮ ───▶ Refresh library   (A12)
        │       Playlists               │          Settings ─▶ F1 Settings
        │ + mini player (when a song    │
        │   is loaded)                  │── tap a song ─▶ plays, queue = this list
        │ + snackbar "N new songs"      │── ⋮ on a song ─▶ E1 Song menu ─▶ E2 Add to playlist ─▶ E3 New playlist
        │   (Show ─▶ Songs, Recently    │── long-press ─▶ E5 Select several ─▶ Add to playlist (E2)
        │   added)                      │── album / artist row ─▶ C1 Album or artist
        └──────┬────────────────────────┘── playlist row ─▶ C2 Playlist ─▶ C3 Edit order
               │ tap mini player                             └─ ⋮ ─▶ Rename (E3) / Delete (E4)
               ▼
        D1 NOW PLAYING ── Queue ─▶ D5 Queue       ── Sleep timer ─▶ D3 choices / D4 running
               │ ⋮ (Add to playlist, Go to ...)
 (V1.1)  Settings ─▶ H3 Song details ─▶ H2 turn on (first time) / H6 Review / H12 Recently filled in / H8 Not found
          Library banner "N songs need your review" ─▶ H6;  song ⋮ ─▶ Edit details H10 ─▶ snackbar Undo
 (V2)    song ⋮ ─▶ Split into songs (4.11, K1 to K8) ─▶ Library banner while a job runs
 (outside the app)
   Widget (G1)  ── tap ─▶ opens D1;  buttons control playback
   Notification / lock screen (G2),  headset buttons,  Android Auto (G3)  ── control the same playback
   File manager "Open with" ─▶ D6 plays the file   (not an MP3 / unreadable ─▶ D7 message)
```

- **Home / first-time view:** the Library on the Songs tab. A first-time user sees A1, then (after allowing) the Library. If nothing is found: A9.
- **Global actions:** Search (magnifier, top bar), Refresh and Settings (⋮ menu), Now Playing (mini player).
- **Way back:** Back from any detail returns to the tab it came from, with the scroll position kept. Back from Now Playing returns to wherever it was opened.

## 3. Style guide

**Direction:** calm and minimal, like a good e-reader for music: one accent color, quiet neutral surfaces, large covers, generous space, and only the controls that matter on screen. Dense only where lists get long.

**Type scale** (Material 3 names; sizes in sp, scale with the system font size):

| Name | Size / weight | Used for |
|---|---|---|
| Title large | 22 / 500 | Screen titles, dialog titles |
| Headline small | 22 / 600 | Song title on Now Playing and album header |
| Body large | 16 / 500 for titles, 16 / 400 for text | Row titles, buttons text 14 / 600 |
| Body medium | 14 / 400 | Row subtitles, dialog text, sheet options |
| Label / caption | 13 / 400, 12 | Counts, times, helper text |

**App icon:** a white music note on the accent teal, as an adaptive icon with a themed (monochrome) layer, and the same note as the small monochrome icon of the notification and of Android Auto's media cards (`docs/mockups/app-icon.png` shows the masks). Android Auto requires both (its manifest guide, read 2026-11-03).

**Spacing:** 4 dp base. Screen margin 16 dp; list row 64 dp tall; touch targets at least 48 × 48 dp; gap between controls 8 or 12 dp.

**Colour and theme** (follows the phone's light or dark setting; a fixed palette, not wallpaper colors, so contrast is known; D-4). Contrast ratios computed with the WCAG formula; text needs 4.5 to 1, icons and borders 3 to 1:

| Role | Light | Dark | Contrast notes (text on its background) |
|---|---|---|---|
| Background | #FAFAF8 | #121314 | |
| Surface (cards, sheets, mini player) | #FFFFFF | #1C1E1F | |
| Text | #1C1B1F | #E6E1E5 | 16.4 / 14.4 on background |
| Secondary text | #4A4A4F | #C7C7CC | 8.4 / 11.0 |
| Accent | #1B6B66 | #7FD1CB | 6.0 / 10.5 on background; text on accent 6.3 / 9.7 |
| Error and warning text | #B3261E | #FFB4AB | 6.3 / 11.0 |
| Chip, selected row | #E8EEEC | #2A2E2E | text on chip 14.6 / 10.6 |
| Divider | #DAD9D6 | #34383A | decoration only |
| Outline (checkbox border) | #74777A | #8E9193 | 4.3 / 5.9 against background; 3.8 / 4.3 on a selected row; all above 3 to 1 |
| Message bar (snackbar) | #2E3133 with text #F1F1EF, action #7FD1CB | #E6E1E5 with text #1C1B1F, action #1B6B66 | action text 7.4 / 4.9 |

High-contrast text (an Android accessibility setting) is left to the system; all text already meets 4.5 to 1.

**Density:** lists use 64 dp rows with a 48 dp cover; Now Playing is spacious; sheets and dialogs are short.

**Hierarchy:** every screen has one primary action: "Play" on detail screens, the pause / play circle on Now Playing, "Allow access to music" on A1, "Try again" on A2. It is the only filled button on the screen. Everything else is outlined, a chip or plain text. Titles are left-aligned; counts and helper text are secondary text.

**Never color alone.** The playing song has a bold title and is spoken as "playing". The selected tab is bold and underlined. Shuffle and repeat show they are on by a ring and a filled circle behind the icon (and repeat one by its own icon with a "1"); a screen reader says "Shuffle on" or "Repeat one". "Can't play" has an icon and the words. Selected rows have a check mark and a shaded row.

**Components and icons:** standard Material 3 components (top app bar, tabs, list items, chips, bottom sheet, dialog, snackbar) and Material icons. The cover is the file's cover; when there is none, a flat square with the artist's first character (mockup) or a music-note icon for files with no artist (D6).

**Motion:** standard Material motion (sheets slide, Now Playing slides up, a short fade). With "Remove animations" on, everything appears without movement. The seek bar and the progress line still move because they show time.

## 4. Screens and states

Each screen below follows the template's key parts. Wireframes are the mockups in `docs/mockups/index.html` (code in brackets). Gestures and their keyboard or TalkBack equivalents are in the interaction tables.

### 4.1 First start and permission (A1, A2, A9)

**Goal:** get from installing the app to hearing a song, without being surprised by a permission.

| State | When | Sees | Can do |
|---|---|---|---|
| First start (A1) | App opened with no permission yet | Why music access is needed; one button | "Allow access to music" opens Android's dialog |
| Refused (A2) | The user said no | "Music access is turned off", why, two buttons | "Try again" (asks again, if Android allows it) or "Open Settings" |
| Allowed, scanning (A13) | Permission given; library being read | Library with a thin progress bar and "Reading your music…" | Wait; the list fills in |
| Notification permission | The first time a song is started | Android's own dialog for notifications, with no screen of ours before it (proposal, D-13) | Allow or refuse; either way the music plays and the lock-screen and car controls keep working (R-9, S-4) |
| Empty (A9) | Allowed, no MP3 found | "No MP3 files found" and how to fix it | "Refresh library" |

| Element | Action | Result | Announced as |
|---|---|---|---|
| Allow access to music | Tap | Android permission dialog; on allow, Library | "Allow access to music, button" |
| Try again | Tap | Asks again; if Android no longer asks, opens Settings | "Try again, button" |
| Open Settings | Tap | Opens this app's page in Android Settings; on return the Library refreshes | "Open Settings, button" |
| Refresh library | Tap | Rescans; shows loading, then list or A9 | "Refresh library, button" |

### 4.2 Library (A3 to A12)

**Goal:** find a song and start it in a few taps (G-1, under 5 seconds).

| State | When | Sees | Can do |
|---|---|---|---|
| Normal (A3, A4) | Songs exist | Tabs, a row with the sort chip and, right beside it, the direction button (an arrow), and the count ("1,248 songs", "96 albums", "12 artists") at the right, list, mini player. Artists has neither chip nor button: it is always A to Z | Tap song, ⋮, search, switch tab, sort, reverse the order, pull down to refresh |
| New songs (A5) | A refresh found songs the app has not announced before | Snackbar "3 new songs found" with "Show", for 8 seconds (longer if the phone's "time to take action" accessibility setting asks) | "Show" switches to Songs, sorted by Recently added, newest first even if the order was reversed (A14) |
| Albums (A6), Artists (A7), Playlists (A8) | Other tabs | Albums: two-column grid of covers, each with the album name, the artist line and "N songs"; Artists: rows with the name and "N songs" (a song tagged "A, B" is listed under A and under B); Playlists: rows (first row "New playlist"). A song or album without a cover shows a flat square with the first character of the artist's name (a note "♪" when there is no artist). With no playlists the tab shows "New playlist" and "Your playlists appear here." | Open an item |
| Loading (A13) | First scan or refresh | Thin progress bar under the tabs, list stays visible | Keep browsing |
| Empty (A9) | No MP3 | See 4.1 | |
| Can't play (A11) | A song failed | "Can't play" with a block icon replaces the artist line | The song can still be tried again by tapping |
| Refresh failed | The media index or the app's copy could not be read | Snackbar "Couldn't read your music." with "Try again", 8 seconds; the list stays as it was. If nothing is listed yet, the same words fill the screen with a "Try again" button | "Try again" refreshes |
| Menu (A12) | ⋮ | "Refresh library", "Settings" | |
| Sort menu (A14) | Sort chip | Title, Artist, Recently added; the chosen one has a check mark and bold text | Choose one; a different field puts the direction back to its default (A to Z, or newest first for Recently added); choosing the field that is already chosen changes nothing |
| Direction (A15) | Direction button | An arrow: up for A to Z or oldest first, down for Z to A or newest first. Each field starts in its default direction: Title and Artist A to Z, Recently added newest first (so its arrow points down at first) | Tap to reverse; the list is exactly the default list backwards |
| Very long titles | Long names | One line, ellipsis at the end; full text on Now Playing | |
| Very many songs | 2,000 | List scrolls smoothly; a fast-scroll handle appears while scrolling (on Songs, Albums and Artists) and shows the initial letter (A to Z, then #) when sorted by Title or Artist, and the month when sorted by Recently added (R-25); in a reversed list the letters (or months) run in the list's own order, so the top of a Z to A list shows Z | Drag the handle to jump. TalkBack ignores the handle; the list scrolls with its normal gestures |
| Large text (A10) | 150% font | Rows grow, nothing is cut off | |

| Element | Action | Result | Keyboard / TalkBack | Announced as |
|---|---|---|---|---|
| Song row | Tap | Plays it; the tapped list becomes the queue; mini player appears | Double-tap | "Song title, artist, album. Double-tap to play" |
| Song row | Long-press | Starts selection (E5) | Actions menu: "Select" | "Song title. Actions available" |
| ⋮ on a song | Tap | Song menu (E1) | Double-tap | "More options for Song title" |
| Tab | Tap or swipe sideways | Shows that tab | Left/right with a keyboard | "Albums, tab, 2 of 4" |
| Sort chip | Tap | Menu: Title, Artist, Recently added | | "Sort: Title, button" |
| Direction button (Songs, Albums) | Tap | Reverses the list; the arrow flips; the fast-scroll handle follows the new order | Double-tap | "Sorted A to Z. Tap to reverse." / "Sorted Z to A. …" / "Sorted newest first. …" / "Sorted oldest first. …" |
| Search icon | Tap | Opens B1 with the keyboard up | | "Search, button" |
| List | Pull down | Refreshes the library (same as the menu item) | "Refresh library" in the menu | "Refreshing" |
| Album tile | Tap | Opens C1 (Stories A7) | Double-tap | "Album name, artist, N songs" |
| Artist row | Tap | Opens C1 (from story C1) | Double-tap | "Artist name, N songs" |
| Snackbar Show | Tap | Songs tab sorted by Recently added; message closes | | "Show, button" |
| Mini player | Tap body | Opens Now Playing (D1) | | "Now playing: title by artist", with the action "Open Now Playing" |
| Mini player | Pause / Next buttons | As labeled | | "Pause", "Next" |

**Opening a file from another app (D6, D7):** Now Playing opens at once whatever was on screen, without needing music access; only MP3 files are accepted (by type, and by their first bytes, not just their name), and a refused file shows the one dialog D7 and changes nothing. Opening a file turns Repeat off and it stays off afterwards, so the file plays once and stops (the user's decision). A file opened this way is remembered as the last song; if the other app gave only temporary access, pressing Play after a restart cannot read it and the song is skipped silently, like any song that cannot be read (R-12). That it needs no music access is checked in the code and by the player tests, not yet with access denied on a phone.

**Built in stages:** Moving TalkBack focus to the first row after "Show", and to the next row after a Remove in a playlist, is not built yet. The mockups in `docs/mockups/index.html` do not cover the states added with C2 (empty Playlists tab, the two unreadable states, a deleted playlist, the several-songs message), and mockup E5 shows the selection without the check mark; the wording in section 5 and this text are the reference. Now Playing has the cover, title, artist and album, "Playing from", the seek bar with both times, Shuffle, Previous, Play or Pause, Next, Repeat (with their state in words) and the Queue button; the sleep timer and ⋮ come with their own stories. The song menu has Play next, Add to queue and Add to playlist (on the Songs tab and in search results); a row in a playlist has Play next, Add to queue and Remove from playlist. The playing song's row shows a bold title and is spoken as "title, artist, album, playing"; a tap on a song row is announced with the action "Play"; the seek bar is spoken as "Seek, 1 minute 34 seconds of 4 minutes 8 seconds".

**Automatic refresh (R-1b):** the library refreshes when the app is opened, when it returns to the front, and when Android's media index changes while the app is in front; a manual refresh (menu or pull down) does the same at once. Text from the files (R-4) is shown exactly as read; if a title shows as garbled characters the app has no way to know, which is why R-4 is Provisional and needs a test on the real files before these screens are final.

**New-songs message (R-22):** it is shown on the Library only; if a refresh finishes while another screen is open, the message waits until the Library is shown. A song is announced once: a later refresh does not find it again, because it is already in the app's copy of the library. The pending count is kept in memory only, so a message still waiting when the app is closed is not shown later (accepted, decision 2026-11-01). "Show" switches to Songs, sets the remembered sort to Recently added, like choosing it from the sort menu, and scrolls the list to the top so the new songs are the first rows. Songs that Android announces within a second of each other are found by one refresh, because the app waits one second after the last media-index notice before refreshing; copying three files gives one message, "3 new songs found".

**"Can't play" mark (R-12):** set when a play attempt fails. It is cleared the next time that song plays successfully, and also when a rescan sees that the file changed (its size or modified time), because a replaced file may play now; a rescan that sees no change does not clear it. Tapping a marked song tries it again, and if it fails it is skipped silently like any other, with no message (the user's decision).

**Remembered view:** the app reopens on the tab, the sort and its direction the user last used, and keeps the scroll position within a session. (The user's decision; the requirement is R-25.)

**Resume:** on opening, the mini player shows the last played song paused at its position (R-11). It does not start by itself on the phone; in the car it does (R-16).

### 4.3 Search (B1, B2)

**Goal:** type a few characters and find the song, album or artist.

| State | Sees | Notes |
|---|---|---|
| Empty field | Keyboard; nothing else | No history list in V1 |
| Results (B1) | Groups: Songs, Albums, Artists | Simplified and Traditional Chinese match each other |
| Nothing found (B2) | "Nothing found for ..." with a hint | |

Interactions: type narrows the list as you type (under 1 second at 2,000 songs, NFR-1); tapping a song plays it with the results list as the queue; a clear (✕) button empties the field; Back closes search (see the table for the keyboard).

If the index is not built yet (a moment after opening, or after a rotation with text in the field), nothing is shown below the field rather than "Nothing found".

| Element | Action | Result | Announced as |
|---|---|---|---|
| Magnifier (Library top bar, left of ⋮) | Tap | Opens search with the keyboard up and the field focused | "Search, button" |
| Back arrow (search top bar) | Tap | Closes search; the Library is as it was left (tab, sort, scroll position) | "Back, button" |
| Field | Type | Results narrow on every keystroke; blank shows nothing; the keyboard's Search key hides the keyboard and keeps the results | "Search your music, edit box" |
| Clear (✕), only when the field has text | Tap | Empties the field; the results go; the keyboard stays | "Clear search, button" |
| System Back | Press | First press hides the keyboard if it is up (Android's own behaviour); then closes search | |
| Result rows | Tap | Songs play (B1); an album or an artist opens C1 (A7) | Songs "title, artist, album"; albums "name, artist line, N songs"; artists "name, N songs" |

Results are listed in Title order whatever the Library's sort is, in three groups with a heading each (Songs, Albums, Artists; a group with no match is left out). Album and artist rows are the same groups as in the Library, so a Simplified and a Traditional spelling of one album show once, and a song tagged "A, B" is found under artist A and under artist B. The shared "Unknown album" and "Unknown artist" groups never match. The text typed is not kept after search closes.

### 4.4 Album, artist and playlist (C1 to C4)

**Goal:** play a group of songs, or see and change a playlist.

| State | Sees | Can do |
|---|---|---|
| Album or artist (C1) | Cover, name, count and length; Play and Shuffle; numbered songs | Play, Shuffle, tap a song, ⋮ |
| Playlist (C2) | Same, plus ⋮ with Rename, Edit order, Delete playlist | As above; "Can't play" songs stay listed and marked |
| Edit order (C3, C5) | Drag handles and a ⋮ on each row; "Done" | Drag by the handle, or open the ⋮ menu: Move up, Move down, Move to top, Remove from playlist; "Done" |
| Empty playlist (C4) | "This playlist is empty" and how to add songs | Back |
| Playlists cannot be read | Damaged file: "Your playlists can't be opened right now." and a line saying the music files are not affected, no button. Any other reason: "Your playlists couldn't be read." with "Try again". The other tabs go on working | Try again (second case only) |
| Playlist deleted while open | "This playlist no longer exists." | Back |

| Element | Action | Result | Announced as |
|---|---|---|---|
| Play | Tap | Copies the list into the queue and plays it from the top with the current shuffle setting; the setting is not changed | "Play, button" |
| Shuffle | Tap | Copies the list into the queue, turns shuffle on and plays | "Shuffle, button" |
| Song in a playlist | ⋮, Remove from playlist | Removed at once; snackbar "Removed "title"" with Undo (E6) | "Removed 後來. Undo" |
| Rename | Menu | Dialog like E3, name filled in | "Rename playlist" |
| Delete playlist | Menu | Confirm dialog E4; Cancel is the default; songs are not touched | "Delete Driving? Cancel, Delete" |
| Drag handle | Drag, or long-press the row and drag (in Edit order a long-press only picks the row up; it never selects) | Reorders; saved at once | "Reorder, Move song up, Move song down" |
| Row ⋮ in Edit order | Tap | Move up, Move down, Move to top, Remove from playlist (C5); the first row has no Move up or Move to top, the last no Move down | "More options for title" |

Edit order is offered only for a playlist of two or more songs. Dragging moves the row one place for every row-height of travel, and every move is saved at once; the row does not follow the finger smoothly, it jumps a place at a time (the menu is the precise way). Play and Shuffle are hidden while the order is edited; Done, or Back, leaves it. Removing a song from Edit order still offers Undo.

### 4.5 Now Playing (D1 to D4, D6)

**Goal:** control what is playing, with the fewest controls that matter.

| State | When | Sees |
|---|---|---|
| Playing (D1) | A song is playing | Cover, title, artist and album, seek bar and times, shuffle, previous, pause, next, repeat, and two icons under the controls: Queue (a list) and Sleep timer (a moon); no printed words, both are spoken ("Queue", "Sleep timer", or "Sleep timer on" with a ring behind the moon while a timer is set) |
| Paused | Paused | Same, with a play button |
| Shuffle on, repeat one (D2) | Set | A ring and filled circle behind the shuffle icon; the repeat icon with a "1" and the same ring. Off has no ring |
| Sleep timer choices (D3, D9) | Sleep timer button | Sheet: 15, 30, 45, 60 minutes and End of song, with no explanation; when one is running the sheet also shows the time left (or "Sleep after this song") and "Cancel timer" (D9). |
| Sleep timer running (D4) | Timer set | "Sleep in 24:10" under the title, counting down; with End of song, "Sleep after this song" |
| From a file (D6) | Opened from the file manager | File name as title, "Unknown artist · Unknown album", note cover; "Playing from: File". The queue holds only that file and playback stops when it ends; it becomes the last played song for resume (the user's decision) |
| Loading a song | First moments of a song | The seek bar shows no progress; controls stay active |
| Can't play | The song fails | Skips silently (R-12); if nothing is left to play, the screen shows the last song paused |
| Sleep timer, time is up | The chosen minutes have passed | Playback pauses at once, in the middle of a song if need be, and the line goes away (the user's decision, 2026-11-02) |
| Sleep timer edge cases | Repeat one is on, or a file was opened from the file manager | The timer applies to whatever is playing, including an opened file; with repeat one, End of song pauses without playing the song again |
| Sleep timer, End of song set while paused | Nothing is playing | It waits for the song that plays next (the usual order is: set the timer, then press Play) |
| Sleep timer, the user acts while "End of song" waits | End of song is set and the song has not ended | Pausing by hand, or starting another list, ends the timer (the line goes away). Skipping to the next song keeps it waiting: playback then pauses when that song ends |

| Element | Action | Result | Announced as |
|---|---|---|---|
| Collapse (✕ down arrow) / Back | Tap | Returns to where it was opened | "Close Now Playing" |
| Play / Pause | Tap | Toggles | "Pause" / "Play" |
| Previous | Tap | After 3 seconds of the song: restarts it; in the first 3 seconds: goes to the previous song. The notification, headset and car buttons use the same rule (the user's decision) | "Previous" |
| Next | Tap | Next song in the queue | "Next" |
| Seek bar | Drag, or swipe up/down with TalkBack (5 s steps) | Moves the position | "Seek, 1 minute 34 seconds of 4 minutes 8 seconds" |
| Shuffle | Tap | On or off; the ring and filled circle appear or go | "Shuffle off, toggle" |
| Repeat | Tap | Off, all, one (cycle) | "Repeat off, button" |
| Queue | Tap | D5 | "Queue, button" |
| Sleep timer (moon) | Tap | D3 | "Sleep timer, button"; "Sleep timer on, button" while a timer is set (a ring shows it too) |
| ⋮ | Tap | Add to playlist, Play next, Add to queue (the same menu as E1) | "More options" |

### 4.6 Queue (D5)

**Goal:** see what will play next and remove songs from it (R-13).

States: normal (Now playing, Next from the list, Added to queue; a failed song shows "Can't play", D5; a song that cannot be loaded further down the queue is marked and taken out of the queue so that the current song carries on (it keeps its mark in the Library and search), while a song that fails when it is reached stays in the queue, marked; the rows are in play order, so with shuffle on they are in the shuffled order, and a heading appears wherever the kind of row changes); empty after the last song (D8, "The queue is empty"). A row's ⋮ has "Remove from queue"; a row can also be swiped away. Tapping a row jumps to it.

### 4.7 Menus, sheets and dialogs (E1 to E6)

| Code | Opens from | Contents | Result |
|---|---|---|---|
| E1 Song menu | ⋮ on a song | Play next, Add to queue, Add to playlist, Delete from phone (red text and the words; R-50) | A snackbar says "Added to queue" or "Will play next" |
| E2 Add to playlist | E1, or E5 | New playlist, then playlists with song counts | Adds; snackbar "Added to Driving". If the song is already in it: no change, snackbar "Already in Driving" |
| E3 New / Rename playlist | E2, A8, C2 | Name field, Cancel, Create (disabled when empty) | Creates or renames |
| E4 Delete playlist | C2 menu | Question, Cancel (default), Delete | Deletes the playlist only |
| E5 Select several | Long-press a song on the Songs tab | "N selected", a close button, "Add to playlist", "Delete"; a selected row shows a check mark in place of its cover and a tinted background; while songs are selected a tap picks or drops a row, and the song menus are hidden. Back, the close button or another tab ends it | Opens E2 for all |
| E7 Delete songs | "Delete from phone" in E1, or "Delete" in E5 | A dialog "Delete from this phone?" with the text "“Rain” will be deleted from this phone and removed from your playlists. This can't be undone." (for several: "12 songs will be deleted ..."), buttons Cancel and Delete. Android may then show its own dialog (without Media management access) | After a yes the message "3 songs deleted"; Cancel or a refusal changes nothing and says nothing. If Android cannot start the request: "Couldn't delete. Nothing was changed." |
| E6 Undo | Removing from a playlist | Snackbar "Removed "title"", Undo, 8 seconds | Undo restores the song in place |

Rules: blocking dialogs are used only to confirm deletion or to report a file that cannot be opened (D7); everything else is a snackbar. Esc and Back close a sheet or dialog without action.

### 4.8 Settings (F1)

Reached from the Library's ⋮ menu ("Settings"). Rows: "Song details" (V1.1), **"Export playlists"** and **"Import playlists"** (R-26, below), "Copy diagnostics" and "About Music Player". "Copy diagnostics" copies the text and shows "Diagnostics copied"; the text has a title line, the app version, the phone, the Android version and, newest first, the recent error codes with their numbers (or "none", or "unavailable" when the log could not be read). "About Music Player" shows the version and the promise (plays the music on this phone; no account or ads; the internet is used only to look up song details, and only if the user turns that on). There is no theme option (follows the phone).

**Export and import (G1, R-26).** "Export playlists" opens Android's "Save as" screen with the name `music-player-playlists.json`; after saving, the message "3 playlists exported. The record of which songs were already checked is included." appears (the second sentence only when the song-details lookup has such a record) (a failure: "Couldn't save the file. It may be empty or incomplete; your playlists are unchanged."; nothing at all to export, no playlist and no lookup record: "There is nothing to export."; with only the record, the file holds just that and the message is "The record of which songs were already checked is included."). "Import playlists" opens Android's file picker, showing only JSON files (the type of the export file); a file of another type cannot be chosen, and a JSON file that is not an export is still refused; after choosing, "2 playlists imported. The check record of 480 songs was restored." (the second sentence only when the file carries it; a record that cannot be restored: "The record of which songs were already checked could not be restored."). The record adds decisions only for songs that have none; it never changes an existing decision. It only adds: playlists already there (same name and same songs in the same order) are skipped, so importing the same file twice says "Nothing to import: these playlists are already here." A file that cannot be read: "Couldn't read or write the file. Nothing was changed." A file that is not an export, or is damaged or empty: "This is not a Music Player playlist file. Nothing was changed." If the app's playlists cannot be read: "Playlists can't be read right now, so nothing was changed." (a damaged database: "The playlists file is damaged, so nothing was changed."). A song of the file that is not on this phone stays in its playlist marked "Can't play", as after a restore (R-17); songs that moved are linked again by file name and size. The messages are snackbars; no dialog. An imported playlist whose name matches an existing one but has other songs is added as a second playlist with that name (names need not be unique, R-10). The rows are plain settings rows like the others: TalkBack reads the title and the explanation, and focus returns to the row when the picker closes.

### 4.9 Widget, notification and car (G1 to G3)

- **Widget (G1, R-24):** one size, 4 × 1: cover, title, artist, previous, play / pause, next. Tapping the cover opens the app (R-24). With nothing playing it shows the last played song and Play resumes it (R-24). When nothing has ever been played it shows "Music Player" and "Tap to open" (proposal). Built in E1: the whole widget opens the app in that state, and the three buttons are drawn only when there is a song; a paused song shows Play, a playing one Pause (and the button's spoken name says which); the widget is redrawn when the song or the play state changes and at no other time, so its play button can be a moment behind if the phone's launcher was slow to apply it.
- **Notification and lock screen (G2, R-9):** title, artist, cover, previous, play / pause, next. Android draws them from the app's media session.
- **Restored playlists (R-17):** after a restore on a new phone, songs that cannot be found there stay in the playlist marked "Can't play"; there is no separate restore screen.
- **Android Auto (G3, R-16):** the browse tree has four tabs, the most Android Auto allows: Playlists, Albums, Artists, Songs. There is no Resume tab: the restored last song is in Now Playing and resumes on connect or on Play (Technical Design 5.5). Long lists in the car get a letter level (A to Z, #) first, then runs of the cap for a long letter, because Android Auto limits list length (Technical Design 5.5). With no music the car shows one line: "Open Music Player on your phone to set up your music." Now Playing shows title, artist and cover, with play, pause, next, previous, shuffle and repeat. Voice requests are handled by the car's assistant. Android Auto draws all of this; the app supplies the data and the rules above.

### 4.10 Song details: automatic fill-in, review, edit and Undo (H1 to H13) — Draft, V1.1

**Goal:** the user's songs get their real title, artist and album with almost no effort, and the user can see, fix and undo what the app did. Requirements: R-19, R-27 to R-31 (Requirements S-7). Images of the whole flow: `docs/mockups/v1.1-flow-1-set-up.png`, `v1.1-flow-2-review.png`, `v1.1-flow-3-edit-undo.png` (the figures H1 to H13 in `docs/mockups/index.html`). Built so far (Stories H2, H3, H8, H9): Edit details, Recently filled in, Undo, Song details with its switches, states and the consent dialog, the Library banner, Review and Not found.

**Task flow**
1. Settings → **Song details** (H1, H3). The switch "Fill in missing details" is off. Turning it on the first time shows one dialog (H2) saying what is sent and to whom; "Turn on" starts the lookup, "Not now" leaves it off. Writing into the song files needs Android's permission: the first choice is the one-time "Media management" switch in Android's settings, which the app explains and opens from the "Allow" button, after which writing needs no more questions; the fallback is Android's per-batch dialog (H13), shown only while the user is in the Review or presses "Allow".
2. The lookup runs by itself in the background. The Library shows nothing new except, when songs are waiting for review, a one-line banner "N songs need your review" with "Review" (H5). Progress is on Song details (H3).
3. **Review** (H6): one held song at a time, with Play so the user can hear it, the best match already selected, and three buttons: Use this / None of these / Skip for now. When the last one is decided, H7 says how many were filled in, rejected and skipped.
4. **Recently filled in** (H12) lists the changes of the last 30 days, the app's and the user's own (a hand edit reads "Edited by you"), each with Undo. **Not found** (H8) lists songs the database did not know or that could not be read; each opens the editor.
5. **Edit details** (H9, H10) is on every song menu (Songs, search, playlist): title, artist and album, Save. After Save a snackbar "Details saved" with Undo (H11). If Android did not allow changing the file, a second message says "Saved in the app only…" with "Allow" (R-27).

**States of Song details (H3, H4)**

| State | Sees | Can do |
|---|---|---|
| Off (first time and after turning off) | The switch Off and one line "Turn on "Fill in missing details" to look up songs." The lists (review, recently filled in, not found) stay available | Turn on (H2 the first time) |
| Running | "Looking up songs…", "214 of 1,248 checked", a thin progress bar; the three list rows with counts | Open a list; turn off (stops at once; nothing is sent after) |
| Waiting for Wi-Fi | "Waiting for Wi-Fi", "700 songs are still to be checked. They continue when Wi-Fi is back." (only while "Only on Wi-Fi" is on) | Turn "Only on Wi-Fi" off to use mobile data |
| Service asked us to wait | "The music database asked us to wait", "It will be tried again tomorrow." (R-19: never retried the same day) | Nothing needed |
| Files not changeable | "Some details are only in the app", with "Allow" | Allow (opens the Media management settings, or Android's dialog if the user prefers) |
| Done | "All songs checked. N need your review." | Review |
| Nothing to look up | "Every song already has a title, artist and album." (only while "Also replace details that are already filled in" is off; with it on every undecided song counts, so the progress line counts complete songs too and this state does not occur) | Nothing |
| Offline | Same as waiting: "No internet right now. It continues when you are online." | |

**Interactions**

| Element | Action | Result | Announced as |
|---|---|---|---|
| Song details row (Settings) | Tap | Opens H3 | "Song details, button. Fills in missing titles, artists and albums. Off." |
| Fill in missing details | Toggle | First time: H2; later on/off at once. Off: no request is made | "Fill in missing details, switch, on" |
| Only on Wi-Fi | Toggle | On by default (R-28) | "Only on Wi-Fi, switch, on" |
| Only look up new songs | Toggle | Off by default (R-51). On: songs already on the phone are left alone; only songs added after the switch was turned on are looked up. The count of songs left to check leaves the older songs out. Turning it off includes them again | "Only look up new songs, switch, off" |
| Also replace details that are already filled in | Toggle | Off by default (R-32). On: songs that have all three details are looked up too and a sure match replaces differing text; Undo brings the old text back | "Also replace details that are already filled in, switch, off" |
| Banner "N songs need your review" | Tap Review | Opens H6 (Review); dismiss hides it until there are new ones | "12 songs need your review. Review, button" |
| Review: Play | Tap | Plays the song from the start (it is in the library); tap again pauses | "Play, button" |
| Review: a choice | Tap | Selects it (filled circle and check mark) | "Title, artist, album, selected, radio button 1 of 3" |
| Use this | Tap | Applies the selected match (and writes it, R-27), shows the next song | "Use this, button" |
| None of these | Tap | Rejects all; the song is not looked up again unless asked (R-29); next song | "None of these, button" |
| Skip for now | Tap | Leaves it in the list; next song | "Skip for now, button" |
| Not found: Edit | Tap | Opens Edit details for that song; after Save the song leaves the list | "Edit details of <file name>, button" |
| Edit details (song menu) | Tap | Opens H10 with the current values | "Edit details" |
| Save | Tap | Writes the three fields (R-30); returns; message with Undo | "Save, button" |
| Undo (snackbar or H12) | Tap | Restores the earlier title, artist and album, in the file too (R-31) | "Undo, button" |
| Back | | From H10 with changes: asks "Discard your changes?" (Cancel / Discard); from other screens one step back | |

Focus: after any decision (Use this, None of these or Skip), focus goes to the first choice of the next song, and the header "Review · 2 of 12" is a polite live region, so TalkBack says the new position. The screens also have these states: **Review, nothing waiting** ("Review" / "No songs are waiting for review." / "Done") and **All reviewed** (the counts, "Done"). **Not found** lists the songs the database did not know or that could not be read, each with Edit, which opens Edit details; the list is empty ("No songs are listed here.") when there are none.

**Decisions (recommendation, runner-up and why not)**

| Decision | Recommendation | Runner-up, rejected |
|---|---|---|
| D-16 Review | One song at a time with a Play button, best match preselected | A single list of all held songs with inline choices: 12 rows of three choices each are dense and the user cannot hear the song |
| D-17 Where the user finds the review | A one-line banner in the Library plus Song details | Settings only: easy to miss; a notification: interrupts, needs the notification permission, and the user said to ask only when unsure |
| D-18 Undo | Per song in "Recently filled in" (all changes, H12) and on the snackbar after a hand edit | "Undo everything" in one tap: too blunt for 1,000 changes |
| D-19 Edit | "Edit details" on the song menu, one song at a time | Editing several songs at once from the selection mode: later, if asked |
| D-20 Progress | A status block on Song details; automatic fills are silent | A progress notification: needs a permission and noise; a message per song: too many |
| D-21 Same song on several releases | One choice: candidates with the same title and artists collapse into the best-scored one, and its first Album is the album shown and written (the user said any album is fine, 2026-11-05; the collapse itself was Claude's recommendation, agreed by the user on 2026-11-05) | Show each release as its own choice: three rows that look the same apart from the album |
| D-22 Replacing details that are already filled in | One more switch on Song details, off by default, with a plain line saying what it does; Undo is the safety net | Always replace (a wrong sure match would overwrite a right tag), or a per-song action only (not what the user asked for) |

**Mockups:** the switch "Also replace details that are already filled in" (D-22) is not drawn in `docs/mockups/` yet; Review shows "Now: title · artist · album" under the song when it has details. **Wording:** in section 5 (Content), the rows "Song details".

**Open (needs the user or a phone):** how many changes back Undo keeps (the design starts with 30 days, Requirements section 15); whether "Play" in review should play only 15 seconds from the middle (easier to recognise than the intro); the actual text of Android's permission dialog (Android draws it, H13 is an illustration); whether the banner should offer "Review later"; TalkBack and largest-font checks of H5 to H12 are not done (mockups only, rendered in a desktop browser).

### 4.11 Split into songs (K0 to K11) — V2

**Goal:** a person with a long MP3 that holds a whole album wants separate songs without typing or listening to the whole thing: the app finds the gaps, they check only the cuts it is unsure about, and one tap writes the songs. Requirements: R-33 to R-49 (Requirements S-8 to S-10). Mockups: `docs/mockups/index.html`, figures K0 to K11.

**Flow**

```
song ⋮ menu ── "Split into songs" ──▶ (checks) ──▶ K1 Finding the songs ──▶ K2 The songs found
   (Library, Search, album/artist)         │                                   │      │
                                           │ folder not allowed ─▶ K7          │      └─▶ K3 Check a cut (one at a time) ◀─┐
                                           │ earlier split ─────▶ K8           │               │ Use / Remove / Next ───────┘
                                           └ cannot read ───────▶ message           ▼
                                                                   K4 Ready to split ──▶ K5 Writing ──▶ K6 Done
                                                                                                            ├─ Show the songs ─▶ Library
                                                                                                            ├─ Delete the original? ─▶ Android's dialog
                                                                                                            └─ Remove these songs ─▶ Android's dialog
```

Back goes one step back, except on K1 and K5, where it sends the job behind the Library (see Background); from K2 it leaves the flow after asking "Leave without splitting?" (nothing has been written). Cancel on K1 and K5 stops safely (R-44).

**Entry and checks**

The song's ⋮ menu gets **Split into songs** for MP3 songs of 6 minutes or more (R-33), in the Library, search results and the new album and artist screens. (Now Playing has no song menu and gets no entry; agreed 2026-11-05.) Tapping it runs quick checks, each with one plain message and a next step:

| Check | If it fails | Message (title / body / buttons) |
|---|---|---|
| The file is gone when the check starts | Nothing is written | "The file is no longer there" / "Nothing was written." / "OK" |
| The file can be read as MP3 (R-39) | Nothing is written | "Can't split this file" / "Music Player couldn't read it as an MP3. The file was not changed." / "OK" |
| Size within the limit (R-34) | Refused before analysis | "This file is too large" / "Files up to 500 MB can be split." / "OK" |
| More songs found than the limit (R-34, known after the analysis) | Stops after K1 | "Too many songs" / "Music Player found 143 songs; it can split up to 120." / "OK" |
| A name is already used by another file (R-41) | Stops before writing | "A file with this name already exists" / "Big album 03.mp3 is already in this folder and is different. Nothing was written." / "OK" |
| An earlier split of this file was interrupted (R-45) | K8 | "Continue the earlier split?" / "7 of 12 songs were written before it stopped." / "Start over", "Continue" |
| The folder takes audio files (R-42) | K7 | "Where should the songs go?" / "Android doesn't let Music Player add songs to the folder this file is in. They can go into Music › {name} instead." / "Cancel", "Use Music folder" |

**Screens**

**K1 Finding the songs** (full screen, title "Split into songs"): the file name, "Finding the songs…", a progress bar with a percentage in words ("40 %"), **Keep going in the background** and **Cancel**. Takes the time the analysis takes (minutes for a large file, up to an hour or more for the largest); the job runs as a background job with a notification, so leaving the app keeps it running (R-43). Back, like **Keep going in the background**, returns to the Library and the job goes on (see Background below); **Cancel** is what stops it, and nothing has been written.

**K2 The songs found** (list):
```
 ← Split into songs
 Big album.mp3 · 12 songs found
 9 look right · 3 need a look        ("All 12 look right" when none needs a look)
 ┌──────────────────────────────────────────┐
 │ 1   0:00 – 4:12     4 min 12 s   ✓ Looks right │
 │ 2   4:12 – 8:30     4 min 18 s   ✓ Looks right │
 │ 3   8:30 – 12:50    4 min 20 s   ! Please check│
 │ …                                              │
 └──────────────────────────────────────────┘
 [ Check the 3 songs ]     (primary; "Check the song" for one)
 [ Split into 12 songs ]   (only while some cuts need a look: they are used as found)
 [ Add a cut ]  [ Cancel ]
```
Each row shows the number, start and end, the length, and a state in words with an icon (never colour alone). Tapping a row opens K3 for the cut at the start of that song (for song 1, the cut at its end). With nothing uncertain the primary button is **Split into 12 songs** and goes to K4. **Add a cut** asks for a time ("Time (minutes:seconds)", for example 8:30; `1:05:30` and a plain number of seconds are accepted too, for files over an hour); a time that is not a time, or closer than 5 s to the start, the end or another cut, is refused in the field with "Use a time like 8:30, at least 5 seconds from the start, the end and the other cuts." (a cut added this way needs no check and is saved in the run record at once, so "Continue" after an interruption shows it; K3 moves it later). Nothing is written to the music folder before K5, whatever is saved in the record. "Continue" on the question about an earlier split opens K4 with the saved cuts and writes only the songs that are missing. If no gaps were found: "No gaps found" / "This file may be one song, or the songs run together. You can add cuts by hand." with **Add a cut** as the primary action (S-9).

**K3 Check a cut** (one cut at a time; "Cut 3 of 11"): shows "Between song 2 and song 3", a time line of the gap with the cut marked, the time ("8:30") and:
- **Play the cut**: plays about 10 seconds before and 10 seconds after the cut from the original (the review takes over playback; nothing is written). Tap again to pause.
- **Earlier** and **Later** buttons that move the cut by 0.5 s (a long press repeats; the time line can also be dragged). The cut can never come closer than 5 s to a neighbour or to either end of the file (R-37); a button that would cross the limit is off and says "Closest allowed".
- Actions: **Use this cut** (primary; goes to the next uncertain cut or back to K2), **Remove this cut** (joins the two songs; for a gap that is not a boundary), **Next** (leave it as it is).
After the last uncertain cut the app returns to K2.

**K4 Ready to split**:
```
 Ready to split
 Big album.mp3 will be split into 12 songs.
 Names      Big album 01 … Big album 12
 Folder     Music › Albums
 Space      Needs 520 MB · 2.1 GB free
 The original stays as it is. Songs get titles later
 from "Fill in missing details", if it is on.
 [ Split into 12 songs ]   [ Back ]
```
If uncertain cuts were not checked, a line says "3 cuts were not checked. They will be used as found." with a **Check them** link. Too little space (R-46): the button is off and the line reads "Not enough space: needs 520 MB, 310 MB free."

**K5 Writing**: "Writing song 5 of 12…", a progress bar, **Keep going in the background** (also Back) and **Cancel**; a notification shows the same and "Done" at the end (R-43). Cancelling: "Stopped. 5 songs were kept." with **Remove these songs** and **OK** (R-44, R-47).

**K6 Done**: "12 songs written" / "They are in Music › Albums." and:
- **Show the songs** (primary): opens the Library sorted by Recently added.
- **Delete the original?** (R-49): the text "Big album.mp3 is still there. Delete it?" with **Keep** (preselected) and **Delete**. Delete deletes the original (Android adds its own dialog only when Media management access is not granted); a refusal leaves everything.
- **Remove these songs** (R-47): a quiet text button; the app asks "Remove 12 songs? This deletes the songs this split made. The original is not touched." with **Cancel** and **Remove**; then Android's delete request for exactly those files.

**Background** (agreed 2026-11-06, like the detail filler): while a job runs behind the Library, a banner above the tabs says what is going on and has one button: "Finding the songs… 40 %" and "Writing song 5 of 12…" with **Open** (back to K1 or K5); when it ends, "Found 12 songs." with **Check them** (K2) and a close button, "12 songs written." with **Show them** (K6) and a close button, or "Stopped. 5 songs were kept." with **Open** and a close button. A running job has no close button: **Cancel** on its screen stops it. Only "Found 12 songs." has a close button, which leaves the flow; the saved run stays, so the menu action offers "Continue" later. "12 songs written." and "Stopped. 5 songs were kept." have **Open** only and no close button, because the screen they open (K6 or the stopped message) holds "Delete the original?" and "Remove these songs", and its **OK** closes it. Tapping the job's notification opens the app at the same place (the banner's screen). If the app was closed meanwhile, it shows a job that is still queued or running first; only when there is none does it open the newest saved run of the last day (the songs found, or the result of the writing). The banner is on the Library only; on other screens the notification is the way back. A failure or a name clash while the screens are hidden shows its message at once. "Split into songs" on the same song while a job is on shows that job; on another song it shows that job too and a short message says why: "Another split is in progress. Finish or cancel it first."

**States**

| Screen | State | What the user sees |
|---|---|---|
| K1 | Working | Progress, Keep going in the background and Cancel; Back is the same as Keep going |
| K1 | Cancelled | Back at the song, nothing written |
| K2 | Normal / all confident / no gaps | As in section 4 |
| K3 | A cut at the edge | The buttons that would cross the 5 s limit are off, with the reason spoken ("Closest allowed") |
| K4 | Not enough space / folder fallback | As in sections 3 and 4 |
| Library | A job behind it | The banner: working (Open), songs found (Check them, closable), written or stopped (Open) |
| K5 | Writing / cancelling / storage fills / source gone | "Stopped: not enough space." or "Stopped: the file is no longer there." with the count kept; no half-written song (R-45); the name clash is caught before writing (section 3) |
| K6 | After a continued split | "12 songs written (7 earlier, 5 now)" |

**Interactions**

| Element | Action | Result | Keyboard / announced |
|---|---|---|---|
| Split into songs (menu) | Tap | Runs the checks, then K1 | "Split into songs" |
| Row in K2 | Tap | Opens K3 at that cut | "Song 3, 8 minutes 30 seconds to 9 minutes 20, please check, button" |
| Play the cut | Tap | Plays 10 s either side; tap again pauses | "Play the cut" / "Pause" |
| Earlier / Later | Tap, long press | Moves the cut by 0.5 s | "Move the cut earlier by half a second"; the new time is announced ("8 minutes 29 seconds and a half") |
| Time line | Drag | Moves the cut | The nudge buttons are the accessible way; the drag is spoken as a slider "Cut position" |
| Use this cut / Remove this cut / Next | Tap | As in section 4 | Buttons with words |
| Split into N songs | Tap | K5 | "Split into 12 songs" |
| Cancel (K1, K5) | Tap | Stops safely | "Cancel" |
| Keep going in the background (K1, K5), Back | Tap, Back | The Library with the banner; the job goes on | "Keep going in the background" |
| Banner | Tap its button | The screen of the job or its result | "Open", "Check them", "Show them"; a result is announced when it arrives |
| Delete the original? | Delete / Keep | Android's confirmation / nothing | "Delete, button" / "Keep, button" |
| Remove these songs | Tap | Android's confirmation for this run's files | "Remove these songs" |

Focus: after Use this cut, focus goes to "Play the cut" of the next cut and TalkBack says "Cut 4 of 11".

**Display conditions**

Largest font: K2 rows wrap to two lines; K3 buttons stack; the time line keeps a 48 dp touch height. Dark theme and high contrast use the player's theme. The time line has a text equivalent ("Cut at 8 minutes 30 seconds; gap from 8:27 to 8:33"). Nothing is carried by colour: states have words ("Looks right", "Please check") and icons.

**Decisions (recommendation, runner-up and why not)**

| ID | Decision | Runner-up, rejected |
|---|---|---|
| D-23 | Check one cut at a time with a Play button, as Review does | A waveform of the whole file with markers: needs much more drawing and is hard to use with TalkBack |
| D-24 | Move a cut with Earlier / Later buttons (0.5 s) plus a draggable time line | Drag only: unusable for a screen reader and imprecise on a small screen |
| D-25 | Play 10 s before and 10 s after the cut | Play the whole next song: slow; play only the gap: silence tells nothing |
| D-26 | Uncertain cuts the user did not check are used as found, with a clear line on K4 | Force the user to decide every one: slower and against "never prompt for every cut" |
| D-27 | The question about deleting the original comes after the success message, with Keep preselected | Asking before writing: the user has nothing to judge yet |
| D-28 | The numbered names are shown in K4 so the user knows what will appear | Hiding them: a surprise in the Library |
| D-29 | The job goes on behind the Library with a banner, like the detail filler (the user, 2026-11-06) | A full-screen progress that Back cancels: the analysis of a long file takes a long time and the player is unusable meanwhile |

## 5. Content

Final wording, in the product's tone (plain, short, no jargon):

| Where | Text |
|---|---|
| A1 title / body / button | "Find the music on this phone" / "Music Player needs to read the audio files on this phone to show your library. Nothing is sent anywhere, and your files are never changed." / "Allow access to music" |
| A2 title / body / buttons | "Music access is turned off" / "Without it, Music Player can't see the songs on this phone. You can allow it now, or in Settings." / "Try again", "Open Settings" |
| A9 title / body / button | "No MP3 files found" / "Copy some MP3 files to this phone, then refresh. Only MP3 files are shown." / "Refresh library" |
| Loading | "Reading your music…" |
| New songs | "3 new songs found" ("1 new song found") / "Show"; shown for 8 seconds by our own timer (Material's fixed lengths are about 4 and 10 seconds), longer if the phone's accessibility timeout asks |
| Refresh failed | Snackbar "Couldn't read your music." / "Try again"; with nothing listed yet, a screen "Couldn't read your music" / "Something went wrong while reading the music on this phone. Try again." / "Try again" |
| Counts | "1 song", "2 songs"; "1 album", "96 albums" |
| Sort menu | "Title", "Artist", "Recently added" |
| Direction button | Spoken only (the arrow carries no text): "Sorted A to Z. Tap to reverse.", "Sorted Z to A. Tap to reverse.", "Sorted newest first. Tap to reverse.", "Sorted oldest first. Tap to reverse." (Title and Artist use the A to Z words) |
| Empty playlist | "This playlist is empty" / "Open the menu on any song and choose "Add to playlist"." |
| Search field / nothing found | Placeholder "Search your music"; "Nothing found for "xyzq"" / "Try a song title, an artist or an album. Simplified and traditional Chinese both work." |
| Search controls | "Search" (magnifier), "Back", "Clear search" |
| Can't play (mark) | "Can't play" |
| Unknown tags | "Unknown artist", "Unknown album"; a missing title shows the file name |
| Song menu | "Play next", "Add to queue", "Add to playlist"; V1.1 adds "Edit details" (4.10) |
| Review and Not found (V1.1) | "Review" / "No songs are waiting for review." / "Done"; "Play" and "Pause" on the Review button; "Selected" is the spoken name of the check mark; a choice reads "Title · Artist · Album" with "Artist unknown" / "Album unknown" when the service has none; Review is also reached from Song details by a row "N songs need your review" (singular "1 song needs your review"), and Not found by a row "N songs were not found" ("1 song was not found"); Not found: "Not found" / "The music database didn't know these songs, or they couldn't be read. Edit a song to fill in its details yourself." / "No songs are listed here." / "Edit" (spoken "Edit details of <file name>") |
| Song details (V1.1) | Settings row "Song details" / "Fills in missing titles, artists and albums. On." (or "Off."); switches "Fill in missing details" / "Looks up the title, artist and album of songs that lack them." and "Only on Wi-Fi" / "Waits for Wi-Fi instead of using mobile data."; "Also replace details that are already filled in" / "When the music database is sure, its title, artist and album replace the ones in the song. Songs that already have all three are looked up too. You can undo this."; consent dialog "Fill in missing details?" / "Music Player makes a short fingerprint of each song that lacks a title, artist or album and asks a free music database (AcoustID and MusicBrainz) what it is. It sends only the fingerprint and the song's length, never the file name or your playlists. The database also sees this phone's internet address." / "To save the details inside your songs, Android will ask for your permission." / "Not now", "Turn on"; the switches' state is written as "On" or "Off" beside each; status lines as in H3 and H4; the Library banner "N songs need your review" with "Review" and a "Dismiss" icon button; lists "N songs need your review", "Recently filled in", "Not found"; review "Review · 1 of 12", "This song", "Which one is it?", "Use this", "None of these", "Skip for now", "All reviewed" / "12 songs decided: 9 filled in, 2 rejected, 1 skipped.", "Done"; editor "Edit details", fields "Title", "Artist", "Album", "Saved in the song file, so other apps and the car show it too. The music itself is not changed.", "Cancel", "Save"; snackbars "Details saved" / "Undo" and "Saved in the app only. Allow changes to your songs to save them in the files." / "Allow" (only when access is the reason; other reasons have their own sentences: "…This song's tag is of a kind Music Player doesn't change.", "…There isn't enough free space to change the song's file.", "…The song's tag is too big to change safely.", "…The song's file couldn't be read."; after Android grants the write, "Saved in 1 song" or "Saved in 214 songs"); discard dialog "Discard your changes?" / "Cancel", "Discard" |
| Snackbars | "Will play next", "Added to queue", "Added to Driving", "Already in Driving", "Removed "後來"" with "Undo", "Diagnostics copied" |
| New playlist | "New playlist" / field "Name" / "Cancel", "Create" |
| Delete playlist | "Delete "Driving"?" / "The playlist is deleted. Your song files are not touched." / "Cancel", "Delete" |
| Selection bar | "3 selected" / "Add to playlist" / close button "Cancel selection"; a selected row is spoken "title, artist, album, selected"; TalkBack's action "Select" does what a long-press does |
| Playlists tab | A count line "4 playlists" at the right (no sort chip) |
| Playlist could not be saved | "Couldn't save the playlist. The phone may be out of space." (also in Technical Design 11) |
| Playlists tab, nothing saved | "Your playlists appear here." under the "New playlist" row |
| Playlists cannot be read | "Your playlists can't be opened right now." / "The playlists saved on this phone can't be read. Your music files are not affected." (damaged file); "Your playlists couldn't be read." / "Something went wrong while reading them. Try again." / "Try again" |
| Playlist deleted while open | "This playlist no longer exists." |
| Adding several songs | "Added 3 songs to Driving"; with some already in: "Added 2 songs to Driving (1 already in it)"; nothing new: "Already in Driving" |
| Playlist screen | Header "12 songs · 48 min" ("1 h 42 min" from an hour up), "Play", "Shuffle"; menu "Edit order", "Rename", "Delete playlist"; row menu "Remove from playlist"; "Playing from: Playlist" |
| Edit order | Title `Edit "Driving"`, "Done" in the top bar; row menu "Move up", "Move down", "Move to top"; the handle is spoken "Reorder" with the actions "Move song up", "Move song down" |
| Now Playing | "Playing from", "Queue", "Sleep timer"; spoken, not printed: "Shuffle on", "Shuffle off", "Repeat off", "Repeat all", "Repeat one" |
| Sleep timer | "Sleep timer" / "15 minutes" ... "60 minutes", "End of song" / "Cancel timer" when running; running lines "Sleep in 24:10" and "Sleep after this song"; no explanatory text |
| File cannot be played (D7) | "This file can't be played" / "It isn't an MP3 file, or it can't be read." / "OK" |
| Settings | "Copy diagnostics" / "Copies the app version, phone model, Android version and recent error codes. It never includes song, artist, file or playlist names." / "About Music Player" / "Version 1.0 · Plays the music stored on this phone. No account, no ads. The internet is used only to look up song details, and only if you turn that on." |
| Car, no permission or empty library | "Open Music Player on your phone to set up your music." (one line) |

## 6. Accessibility and platform notes (NFR-5, NFR-6, NFR-8)

- **Screen reader (TalkBack):** every control has a label (listed in the tables). Rows read "title, artist, album". The seek bar is adjustable. Gestures with an alternative: long-press selection has "Select" in the actions menu; swipe to remove from the queue has "Remove from queue"; drag has "Move up" and "Move down".
- **Order and focus:** top bar, tabs, sort, list, mini player. After closing a sheet or dialog, focus returns to the button that opened it. After "Show" focus goes to the first list row. After Remove, focus goes to the next row (not built yet for playlists: focus stays where Android puts it).
- **No color alone:** see section 3. Checked in every mockup.
- **Text scaling:** layouts grow with the system font size to 200%; the mockup A10 shows 150%. Long titles ellipsize on one line in lists and wrap to two lines on Now Playing.
- **Smallest screen:** assumed 360 × 640 dp (the requirements say only "phones"). The mockups are 360 × 720. Now Playing must fit at 640 tall without scrolling at normal text size and scroll at large text.
- **Themes:** light and dark follow the phone; both palettes are checked above. High-contrast text is the system's.
- **Edge to edge:** the app draws behind the status and navigation bars. The mini player and every other control at the bottom sit above the system navigation bar (gesture bar or three buttons), with the strip's background reaching the screen edge.
- **Touch targets:** 48 dp minimum, including the seek bar thumb area and the ⋮ buttons.
- **Keyboard:** phones rarely have one, so only the main tables list keys. For every screen: all controls are standard focusable components; Tab and Shift+Tab move in reading order (top bar, tabs, sort, list, mini player); arrow keys move between tabs and within lists; Enter or Space activates; Esc or Back closes a sheet, menu or dialog. The Sort chip, Search icon and every button in the tables follow this.
- **Interruptions:** a phone call pauses and the notification stays; unplugging headphones pauses (R-9). If the phone disconnects from the car, playback stops and the app shows paused at the same position (S-2). Nothing asks for input while the car is connected.
- **Reduced motion:** see section 3.
- **Departures from Android conventions:** one: the Library uses top tabs rather than a bottom bar (D-1).

## 7. Decisions

| # | Decision | Recommendation | Runner-up, and why rejected |
|---|---|---|---|
| D-1 | Library navigation | Top tabs (Songs, Albums, Artists, Playlists) and Search in the app bar | Bottom navigation bar: adds permanent chrome for what is one area; the user asked for calm and minimal |
| D-2 | Now Playing | Full screen that slides up from the mini player | An expanding sheet over the Library: more complex gestures, harder for TalkBack |
| D-3 | Reordering playlists | Drag handles plus Move up / Move down | Drag only: not usable with TalkBack and hard to be precise |
| D-4 | Colors | One fixed palette with checked contrast | Wallpaper-based "dynamic color": looks personal but contrast cannot be checked in advance |
| D-5 | Selecting several songs | Long-press starts selection | Check boxes always visible: adds clutter to every row |
| D-6 | Sorting | A sort chip with a menu on Songs and Albums | Separate "Recently added" tab: a fifth tab and a second list to keep in step |
| D-7 | Search results | Grouped (Songs, Albums, Artists) | One mixed list: harder to scan with 2,000 songs |
| D-8 | Messages | Snackbars for passing news; dialogs only for deletion and an unplayable opened file | Dialogs for everything: interrupts listening |
| D-9 | Deleting a playlist | Confirm dialog | Undo snackbar: friendlier, but needs the data kept for a while; rejected for V1 |
| D-10 | Widget | One 4 × 1 size | Several resizable sizes: more layouts to test for little gain |
| D-11 | When "Can't play" is set | After a failed attempt | Checking all 2,000 files up front: slow, drains battery, and the user chose silent skipping anyway |
| D-12 | Opening the app | Last played song shown paused in the mini player | Starting to play at once: surprising in a quiet place |
| D-15 | Reversing the sort | A direction button beside the sort chip, with an arrow and a spoken state (the user asked for it on 2026-11-01 after using the Library on the test phone) | Tapping the chosen menu option again to flip it: invisible, and gives no sign of which way the list runs. Not built: the direction is not kept per field (changing the field resets it) |
| D-14 | Playlist versus queue | Separate: starting a playlist copies it into the queue; shuffle, "Play next" and "Add to queue" change only the queue; editing a playlist while it plays does not change the queue (the user's decision) | Playing a playlist directly as the queue: simpler, but shuffling or adding a song would change the playlist |
| D-13 | Notification permission | Ask the first time a song is started, with Android's own dialog and no screen of ours; refusal changes nothing the user sees | Ask at first launch together with music access: two permission dialogs in a row before any music, and the reason is less clear |

## 8. Risks and open questions

**Risks**
- Chinese tags may display wrongly (A-2); screens assume correct text.
- The mockups were not rendered on an Android device; spacing and fonts will differ.
- Android Auto limits (list depth, artwork) may force changes to the browse tree.
- Moving songs around in a 120-song playlist by dragging is tedious; a "Move to top" action may be needed.

**Open questions for the user:** none about behaviour. What remains is to confirm or strike the proposals listed below, and to check the app name for availability.

**Answered by the user on 2026-10-25:** Play does not change shuffle and a playlist is separate from the queue (D-14); Chinese titles sort by Hanyu Pinyin (R-25); a fast-scroll handle (R-25); the working name is "Music Player" (not yet checked for availability on Google Play); the 8-second messages are right; the Library remembers the last tab and sort; tapping a "Can't play" song fails silently again; a file opened from the file manager replaces the remembered last played song; Previous restarts the song after 3 seconds.

**Proposals of this design that the requirements do not state** (nothing here is agreed until the user says so): "Cancel timer" and the running time inside the sleep timer sheet; the sleep timer button on Now Playing (Requirements section 5 listed the timer under Settings); no search history in V1; Settings holding only Copy diagnostics and About; the sleep timer and queue not being offered on Android Auto; "Move to top" in the row menu; the widget text when nothing has ever played; the notification permission timing (D-13); Latin titles mixed with pinyin in the same A to Z order.

## 9. How to check it

Run on the phone when the screens are built:
1. Fresh install; open the app. → A1 shows; allow; the library appears.
2. Repeat after choosing "Don't allow". → A2 shows; "Open Settings", allow, return; the Library fills without restarting.
3. Tap a song. → It plays, the mini player appears, and the Library list is the queue.
4. Open Now Playing; turn shuffle on and repeat to "one". → The shuffle icon gets a ring and filled circle, and the repeat icon changes to the "1" icon with the ring.
5. Lock the phone. → Music continues; the lock-screen controls pause and skip.
6. Copy 3 MP3 files to the phone; return to the app. → The message "3 new songs found" appears; Show lists them first.
7. Make a playlist from three selected songs; reorder with the handle and with Move up. → Order is kept after closing the app.
8. Delete one of those files outside the app, then play the playlist. → It skips silently and the entry is marked "Can't play".
9. Set the sleep timer to 15 minutes. → After the time runs out, playback pauses at once. Set End of song → playback pauses when the song ends.
10. Turn on 200% font size and dark theme; repeat 3 and 4. → Nothing is cut off; every state has words as well as colors.
11. Add the widget to the home screen. → It shows the current song and its three buttons work.
12. Connect to the car. → The last played song starts by itself; browse Playlists on the car screen.
13. (V1.1) Open Settings → Song details at 200% font size and in dark theme: turn the switch on the first time → the consent dialog shows all its text and both buttons; the status line, both switches and "Allow" are fully visible; with held songs the Library banner shows its count, "Review" and "Dismiss" without clipping; Review shows the song, Play, every choice and the three buttons (scroll if needed) and the selected choice is marked by a check mark and the word "Selected" as well as the filled circle; Not found shows each row with Edit. → Every state has words as well as colours.

Screenshots needed for the review after building (on the test phone, light and dark): A1, A2, A3, A5, A9, A10, B1, C2, C3, D1, D2, D3, D5, E1, E2, E5, F1, G1, and the car screens.

Screenshots I produced here: renders of the mockups in `docs/mockups/index.html` in Microsoft Edge on the user's PC, looked at by me only. Dark appears for A4, D2, G1 and G2, and large text for A10. States designed in this document but not drawn: a very long title row, the widget when nothing has ever played, and the car's one-line message. Could not produce: anything on a real device or emulator, TalkBack behaviour, the real widget picker, the real notification, and Android Auto.
