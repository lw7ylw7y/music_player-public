# Requirements: Music Player

> Name: "Music Player" (the user's decision, 2026-11-03; it was "Song Player" before). Not yet checked for availability or trademark on Google Play; the name is generic, so check before any store submission. The repository, folders and code identifiers (package `app.songplayer`) keep their old names.

| | |
|---|---|
| Status | Agreed on 2026-10-25 (the user). The V1.1 automatic-metadata requirements (R-19, R-27 to R-31) were rewritten and added on 2026-11-03 and agreed by the user the same day; R-32 (replace existing details, optional) was added on 2026-11-05 at the user's request. The splitter requirements (V2, R-33 to R-49) were agreed in principle on 2026-11-05 and built by 2026-11-06. Requirements marked Provisional still depend on the assumptions in section 11. |
| Last updated | 2026-11-06 |
| Platform | Mobile (Android phone, with Android Auto) |
| Related | docs/Technical Design.md, docs/UX Design.md and docs/Stories and Test Plan.md; the command-line splitter `cli/` (`cli/README.md`) |


## Summary

- **What:** A simple offline music player for Android that plays the MP3 files stored on the phone, including in the car through Android Auto.
- **For whom:** The user, daily, on an Android 13 phone; others may install it free from Google Play.
- **Problem it solves:** YouTube Music treats music stored on the phone as second-class and has many features the user does not want.
- **First usable version includes:** finding the music on the phone, showing title, artist, album and cover, search, play, shuffle, repeat, playlists, queue, sleep timer, opening an MP3 from the file manager, a home-screen widget, resume of the last song and playlist, phone and lock-screen controls, and full Android Auto. Automatic metadata (filled in and saved into the songs) and lyrics come in V1.1; the splitter comes in V2.
- **Biggest risk or open question:** Whether Chinese titles and artists in the user's files display correctly; and how much work full Android Auto adds to V1.
- **V2:** a long MP3 that holds many songs (an album ripped as one file) gets "Split into songs" on its menu: the app finds the gaps, the user checks the uncertain cuts, and one new MP3 per song is written next to the original (R-33 to R-49).

## 1. Problem

The user keeps music as MP3 files on the phone and plays it with YouTube Music. In YouTube Music, files stored on the phone are second-class: finding and playing them is harder than playing streamed songs, and the app is full of features the user does not use. The user wants a small app that does one thing well: play the music that is on the phone, on the phone and in the car.

V2 (the splitter): the user also has MP3 files that are albums or compilations ripped as one file, 5 to 120 songs each. Today the only way to listen is to drag to a random position, and the player cannot show which song is playing. The command-line tool `cli/splitmp3.py` splits them well on a PC, but needs a PC and a copy back to the phone.

## 2. Users and goals

**Users:** Mainly the user, every day, on an Android 13 phone, including in the car through Android Auto (USB or wireless). The library is 500 to 2,000 songs, mostly Chinese music in MP3 files. Other people may install the app from Google Play; the design is for the user's use first.

**Goals:**
- G-1: Any song in the library is playing within a few taps and 5 seconds of opening the app.
- G-2: Playback keeps working with the screen off, in a pocket, on Bluetooth and in the car.
- G-3: Songs show the right title, artist, album and cover, including Chinese text.
- G-4: The app does nothing the user did not ask for: no streaming, no recommendations, no accounts, no ads, no tracking.
- G-5 (V2): Split a long MP3 into one MP3 per song, on the phone.
- G-6 (V2): Never lose or damage anything when splitting: the original is never changed or moved and is deleted only if the user says yes after a successful split (R-49); nothing is written before the user confirms; an interruption leaves no half-written song.
- G-7 (V2): Let the user check and adjust the cut points, one song at a time, only for the uncertain ones (and any others they choose to step through).
- G-8 (V2): Get titles, artists and albums for the new songs without typing: the lookup of V1.1 names them.

**Non-goals:**
- Streaming or any online music.
- Recommendations, or autoplay of songs the user did not pick.
- Accounts, sign-in, ads, analytics or tracking.
- Audio formats other than MP3.
- An equalizer or sound effects.
- Pinyin search (typing Latin letters to find Chinese titles).
- Lyrics are not in V1; they are planned for V1.1 (R-23).
- The splitter does not edit songs (trim, fade, volume), re-encode, write tags or cover art at split time, guess titles from file names, or modify or move the original. Trimming silence at the edges of songs is a backlog item (section 13).

## 3. Success criteria

After a month of daily use, the user:
- no longer opens YouTube Music for music stored on the phone;
- finds any song quickly: from opening the app to the song playing takes under 5 seconds on the test phone with up to 2,000 songs;
- has had no unexpected stop of music with the screen off, on Bluetooth or in the car;
- sees correct title, artist, album and cover for the library, Chinese titles included.

**The splitter (V2)**, measured on the user's real files (5 songs up to 120; the largest is the main test):
- At most 2 in 100 of the cut points need manual adjustment (for 120 songs, at most 2).
- The original is byte for byte the same after the split (until the user deletes it, R-49).
- Every new file plays and starts and ends at its cut, to the nearest MP3 frame that can start a song, and together the new files hold every audio frame of the original, in order.
- No confident cut is wrong on the test file (checked by ear or against the user's list).
- The user never enters cut points one by one for a whole album; correctness takes priority over speed.

## 4. Key scenarios

### S-1: Play a song at home
1. The user opens the app; the library is shown at once, with no sign-in.
2. The user finds a song by browsing (Songs, Albums, Artists, Playlists) or by typing in search, and taps it.
3. It plays, and the rest of the list it was picked from follows as the queue.
4. The user locks the screen or switches apps; music continues. The user pauses, skips or goes back from the notification, the lock screen or the headset buttons.
5. The user turns shuffle or repeat on or off.
6. Later the user opens the app, and it shows the last played song and playlist ready to play, at the position where it stopped.

**When it goes wrong:**
- A song cannot be played (file deleted, moved or damaged): the player skips to the next song with no message (the user's choice); the song is marked "Can't play" in the library lists. If every remaining song fails, playback stops.
- A phone call or another app takes the audio: playback pauses, and continues when the audio comes back.
- The permission to read music is refused: S-4.

### S-2: Play in the car with Android Auto
1. The user connects the phone to the car (USB or wireless); the last played song starts by itself when Android Auto allows it (its own "start music automatically" setting decides).
2. The car screen offers the app. The user browses playlists, albums, artists or songs on the car screen, or sees the last played song in Now Playing and presses Play.
3. Now playing shows title, artist and cover; play, pause, next, previous, shuffle and repeat work from the car screen and the steering-wheel buttons.

**When it goes wrong:** An unplayable song is skipped as in S-1. If the library is empty or the permission is missing, the car screen says so in one line and tells the user to open the app on the phone. If the phone disconnects mid-drive, playback stops on the car and the phone shows the app as paused at the same position. The app never asks the user to do something on the phone screen while driving, except by that one line.

### S-3: Make and use a playlist
1. The user makes a playlist, names it, and adds songs from a song's menu or by selecting several songs.
2. The user reorders or removes songs, renames or deletes the playlist.
3. The user plays the playlist, or, while something is playing, adds a song to the queue or plays it next, opens the queue to see it, and removes a song from it.

### S-4: First start and permissions
1. The user installs and opens the app. The app explains in one sentence why it needs to read the music on the phone and asks Android for permission.
2. After the user allows it, the library appears.

**When it goes wrong:** If the user refuses, the library stays empty and says why, with a button to try again and a button to open the phone's Settings. If the user refuses notifications, music still plays, and the controls still work from the lock screen and the car.

### S-5: Sleep timer
1. The user picks 15, 30, 45 or 60 minutes, or "End of song", on the sleep timer before sleeping and starts playback.
2. When the chosen time runs out, playback pauses. With End of song, it pauses when the song that is playing ends. The remaining time is visible until then.

**When it goes wrong:** If the phone is restarted or the app is killed before the timer ends, the timer is gone and playback has stopped with it; the app starts with no timer.

### S-6: Open an MP3 from the file manager
1. In the file manager the user taps an MP3 and chooses this app.
2. The app opens on Now Playing and plays that file; the library is unchanged.

**When it goes wrong:** If the file is not an MP3, or cannot be read, the app shows one short message saying so (Claude's proposal, to confirm) and plays nothing.

**When it goes wrong:** A song in a playlist whose file is gone stays in the playlist, marked "Can't play", and is skipped silently in playback. The user can remove it. Deleting a playlist does not delete any song file.

### S-7: Missing details are filled in (V1.1)
1. The user turns the feature on once, after the app says what it sends (R-19), and may choose "Only look up on Wi-Fi" (R-28).
2. In the background the app recognises songs that lack a title, artist or album from their sound (and, only if the user turned on R-32, songs that already have them), applies the sure matches (R-29), and writes them into the song's tags (R-27).
3. The songs it is unsure about are collected for the user, who goes through them and accepts or rejects each (R-29).
4. The user can change any song's details by hand (R-30) and undo a change (R-31).

**When it goes wrong:** With no internet (or no Wi-Fi when "Only on Wi-Fi" is on) nothing is sent and the lookup waits. If the service is unavailable or the daily or per-second limit is hit, the app stops and shows that some songs are still waiting; it tries again when the connection or the service is back, but not before the next day after a daily or quota limit (R-19); the library works as before. A song that is not recognised is left as it is and listed as not found. If the files cannot be changed (permission declined, a write fails), the details stay in the app only, with a plain message.

### S-8: Split an album file (V2)
1. In the Library, in search results or on an album or artist screen the user opens the menu of a long song and chooses "Split into songs".
2. The app reads the file and finds the gaps between songs (progress is shown) and proposes a cut in the middle of each gap.
3. Cuts the app is confident about are accepted. Uncertain cuts are shown one at a time; for each, the user can play it, confirm it, or move the cut point.
4. The user can also step through any song, confident or not, and place cuts by hand if no gaps were found.
5. The user confirms. The app writes one new MP3 per song into the same folder as the original, named `<original name> <number>`. It keeps going behind the Library if the user leaves the screen or the app, and says when it is done.
6. The new songs appear in the Library after the refresh. If "Fill in missing details" is on, the lookup then names them (R-19).

**When it goes wrong:** an unreadable or corrupt file writes nothing and says so in plain words; a file with no detectable gaps offers manual cuts (S-9); cancelling at any time leaves no partial song and the original unchanged, and the songs already finished are kept and listed; an interruption keeps the songs already written and a rerun finishes the rest without duplicates (S-10); too little free space is shown before writing starts; the source disappearing or storage filling up during the writing stops the job with a plain message and leaves no half-written song.

### S-9: No gaps are found (V2)
The app finds no usable gap (a single song, or a live recording). It says so and offers to place cuts by hand, then continues as S-8 step 5.

### S-10: The job is interrupted (V2)
The phone restarts, the app is killed, or the storage is pulled mid-write. The songs already finished stay. When the user opens the same song's menu again, the app offers to continue the earlier split (it remembers the cuts) or to start over; continuing writes only the missing songs.

### S-11: Delete a song I do not want
The same song is listed twice, or a song is no good. The user opens its ⋮ menu (or selects several songs) and taps Delete. The app asks once and names the song or the count. After a yes (and Android's own dialog if it appears) the files are gone from the phone, the songs leave the lists and every playlist, and a message says how many were deleted. "Cancel" or a refusal changes nothing. **When it goes wrong:** Android cannot make the request: nothing is deleted and the message says so; the user refuses in Android's dialog: nothing changes and there is no message; a song that was playing or queued is skipped as a song that cannot play (R-12).

## 5. User experience

- **Main screens or views:** Library (tabs: Songs, Albums, Artists, Playlists), Search, home-screen widget, Now Playing, Queue, Playlist detail, Settings (Copy diagnostics, about; the sleep timer is a button on Now Playing), empty and permission states; V2 adds the splitter's screens (menu action, "Finding the songs", "The songs found", "Check a cut", "Ready to split", "Writing", "Done") and a banner on the Library for a splitting job. The screens are in docs/UX Design.md.
- **Reference apps (like / dislike):** None named yet. Disliked: YouTube Music's many tabs, home feed and features.
- **Visual style:** Calm and minimal: few controls, big album art, space around everything. Light or dark follows the phone's setting. The app opens on the Library only (no "Recently played" or other feeds).
- **Sketches or mockups:** `docs/mockups/index.html` (the player's V1.1 screens, and the splitter's screens and the Library banner).

## 6. Functional requirements

| ID | Requirement | Priority | Phase | Status | Acceptance criteria | Scenario |
|---|---|---|---|---|---|---|
| R-1 | The app finds the MP3 files stored on the phone that Android makes available to apps, and keeps the library current. | Must | 1 | Provisional (A-1) | (a) Every MP3 that Android makes available is listed. (b) A newly copied MP3 appears automatically when the app is reopened or returns to the front. (c) A manual refresh action makes newly copied MP3s appear at once. (d) Non-MP3 audio files are not listed. | S-1 |
| R-2 | If permission to read music is refused, the library says why and offers "Try again" and "Open Settings". | Must | 1 | Agreed | With permission refused the library is empty and shows the explanation and both buttons; after allowing it in Settings the library fills without restarting the app. | S-4 |
| R-3 | The library can be browsed by Songs, Albums, Artists and Playlists. Each song shows title, artist, album and cover; a missing title shows the file name, a missing artist or album shows "Unknown artist" or "Unknown album", a song tagged with several artists (separated by a comma, the full-width or ideographic comma, or a semicolon) is listed under each of them in Artists, and "&", "/" and "feat." do not split a name, and a missing cover shows a placeholder. | Must | 1 | Agreed | On a set of files with full, partial and no tags, each case shows as described; a song tagged "A, B" is under artist A and under artist B, and "Simon & Garfunkel" is one artist. | S-1 |
| R-4 | Chinese text in titles, artists and albums displays correctly. | Must | 1 | Provisional (A-2) | On the user's real files, no title, artist or album is garbled; the number checked is reported. | S-1 |
| R-5 | The user can search by title, artist or album. Simplified and Traditional Chinese match each other. | Must | 1 | Agreed (1 s Proposed) | Typing narrows the list; Chinese characters work; typing 张 finds 張 and typing 張 finds 张 (and the same for other characters that differ between the two scripts); pinyin is not required; results appear in under 1 second at 2,000 songs (NFR-1, proposed). | S-1 |
| R-6 | Tapping a song plays it. The list it was tapped in becomes the queue, which is separate from any playlist: shuffling, "Play next" and "Add to queue" change the queue only. Play, pause, next, previous and seeking work. | Must | 1 | Agreed | Tapping a song in a list of N songs plays it, and next and previous move through that list; reordering the queue or adding to it leaves the original playlist unchanged. | S-1 |
| R-7 | Shuffle: when on, every song of the queue plays once, in random order, before any repeats. Turning it off restores the original order with the current song kept. | Must | 1 | Agreed | In a queue of N songs with repeat off, shuffle plays exactly N distinct songs and stops; turning shuffle off keeps the current song and continues in list order. | S-1 |
| R-8 | Repeat has three modes: off, repeat all, repeat one. The mode is shown by the shape of the icon and a ring behind it when it is on (not by color alone) and is spoken by a screen reader as "Repeat off", "Repeat all" or "Repeat one" (changed 2026-11-01 at the user's request: the printed word was dropped). | Must | 1 | Agreed | Each mode behaves as named at the end of the queue and when skipping with next. | S-1 |
| R-9 | Playback continues with the screen off or in another app, and is controlled from the notification, the lock screen, and headset and Bluetooth buttons. A phone call or another app's audio pauses it, and it resumes when the audio returns. Playback also pauses when wired headphones are unplugged or a Bluetooth headset disconnects. | Must | 1 | Agreed | During playback, locking the screen, switching apps and pressing a headset button behave as named; after a call ends, playback that was playing resumes; unplugging the headphones during playback pauses it, and it does not resume by itself. | S-1 |
| R-10 | Playlists: create, rename, delete, add songs (from a song's menu or by selecting several), remove songs, reorder, and play. A song can be in several playlists but only once in the same playlist (adding it again changes nothing and says "Already in <playlist>"); playlists never copy or change the files. | Must | 1 | Agreed | Each action works and survives closing and reopening the app; deleting a playlist leaves the song files untouched. | S-3 |
| R-11 | The app remembers the last played song, the position in it, the queue it came from (the last playlist or list), and the shuffle and repeat settings, and restores them at the next start. | Must | 1 | Agreed | After force-closing the app mid-song, reopening shows the same song at the same position (within 5 seconds), the same queue, shuffle and repeat. | S-1 |
| R-12 | A song that cannot be played is skipped silently, and marked "Can't play" (icon and word). The mark is set after a failed play attempt and shown on that song wherever it is listed (library lists, playlists, queue); a song whose file is gone from the phone leaves the library but stays in its playlists with the mark. If no remaining song can be played, playback stops with no message (the user's choice; a deliberate exception to the working agreement's fail-loudly rule, see section 16). | Must | 1 | Agreed (playlist handling to confirm, section 15) | With a deleted file in the middle of a queue, the next song plays with no message; the song shows "Can't play"; no endless skipping occurs with repeat on and all songs bad. | S-1 |
| R-13 | Queue: "Play next" and "Add to queue" on any song; the queue can be viewed and songs removed from it. | Must | 1 | Agreed | "Play next" inserts the song right after the current one; "Add to queue" appends it. | S-3 |
| R-14 | Sleep timer: the user picks 15, 30, 45 or 60 minutes, or "End of song". When the chosen time runs out, playback pauses. With "End of song", playback pauses when the song that is playing ends. The remaining time is visible. | Must | 1 | Agreed (changed 2026-11-02) | After the chosen time playback pauses at once, once; with "End of song" it pauses at the end of the current song; cancelling the timer works. | S-5 |
| R-15 | An MP3 opened from another app (for example the file manager) plays in this app, without being added to the library. | Must | 1 | Agreed (failure message proposed) | Choosing this app for an MP3 in the file manager starts playback of that file; a file that is not an MP3 or cannot be read shows one short message and plays nothing. | S-6 |
| R-16 | Android Auto: the app appears on the car screen. The user can browse Playlists, Albums, Artists and Songs, see the restored last played song in Now Playing and resume it, and see Now Playing with title, artist and cover. Play, pause, next, previous, shuffle and repeat work from the car screen and the steering-wheel buttons. When the phone connects to the car, the last played song starts playing by itself when Android Auto allows it. Spoken requests to the car's assistant ("play <song, artist, album or playlist>") play the match. | Must | 1 | Provisional (A-3; if it cannot be done, V1 is released without it) | Checked on the user's car and on Google's Android Auto test tool: each named action works; the app has no Resume entry in the browse list; the screens follow Android Auto's rules for media apps. Connecting the phone starts the last played song with no tap when Android Auto's setting allows it; what the user's car does is reported. A spoken request plays the right item when the assistant understands it; how often it understands Chinese names depends on Google's assistant and is reported, not guaranteed. | S-2 |
| R-17 | Playlists survive a reinstall or a new phone, restored automatically. The remembered playback state (R-11) does not need to. | Must | 1 | Provisional (A-4; if it cannot be done, V1 is released without it) | After a reinstall or a restore to another phone using the phone's standard backup, the playlists have the same names and songs in the same order. | S-3 |
| R-18 | V1 has no internet access at all. (V1.1 adds it for the metadata lookup only: R-19, decision 2026-11-03.) | Must | 1 | Agreed | In V1 the permissions the app declares do not include internet access (NFR-4). In V1.1 internet is declared only for the lookup (R-19) and nothing is sent while the feature is off. | S-1 |
| R-19 | V1.1: the app recognises songs from their sound and fills in the missing title, artist and album by itself. It never guesses from a file name. The first time, it says what it sends (a short audio fingerprint and the song's length; the lookup service also sees the phone's internet address and the app's name) and asks once to turn the feature on; the user can turn it off at any time and nothing is sent after that. By default only missing title, artist and album are filled in and a tag that exists is not replaced by the lookup (the user's decision, 2026-11-03); the user can turn on replacing (R-32, decision 2026-11-05). If the service is unavailable or a limit is reached, the app stops, keeps what it has, and tells the user that some songs are waiting; the library works as before. It does not retry a call the service refused for a quota or a daily limit until the next day, and never tries in a tight loop. | Should | 1.1 | Provisional (A-5, A-10) | Tested with a fake service that records every request: with the feature off, no request is made; no request contains a file name, tag or playlist; a refused-for-limit answer stops the lookup until the next day; with the service down nothing breaks and the library lists as before. Measured on the user's 120-song album and its title list: the report gives how many songs were matched, held for review (R-29) and not found; the targets are open questions. | S-7 |
| R-21 | Settings has a "Copy diagnostics" action that copies the app version, the phone model, the Android version and recent error codes to the clipboard, so the user can paste them into a message. It never includes song titles, artists, file names or playlist names. | Should | 1 | Agreed | The copied text contains only the items named; a check on a library with distinctive names finds none of them in it. | S-1 |
| R-22 | Newly found songs are easy to see: Songs and Albums can be sorted by "Recently added" (newest first), and after any refresh that finds new songs a one-line message "N new songs found" with a "Show" button appears once. "Show" opens Songs sorted by Recently added. The first scan after install (an empty library) shows no message. | Must | 1 | Agreed | Copy 3 MP3s to the phone and refresh: the message says "3 new songs found"; "Show" lists those 3 at the top; the message does not appear again for the same songs; the very first scan after install shows no message. | S-1 |
| R-23 | V1.1: lyrics. While a song plays, the Now Playing screen can show its words. Sources: lyrics stored in the MP3 file and `.lrc` files next to it (words timed to the music). Generating lyrics from the audio on the phone is to be investigated and is not committed. | Should | 1.1 | Provisional (A-6) | For songs that have lyrics in one of the named sources, the words show and, when timed, follow the music. A song without lyrics shows a short "No lyrics" line. | S-1 |
| R-24 | A home-screen widget shows the current song's cover, title and artist, with previous, play or pause, and next buttons; tapping the cover opens the app. | Must | 1 | Agreed | With a song playing, the widget shows it and each button works; with nothing playing, the widget shows the last played song and Play resumes it. | S-1 |
| R-25 | Sorting and scrolling. Songs and Albums can be sorted by Title, Artist or Recently added (R-22), and the order can be reversed; each field starts in its natural direction (A to Z, newest first). Chinese titles and names are ordered by Hanyu Pinyin (the mainland standard); titles in Latin letters are ordered in the same A to Z order. While scrolling a long list sorted by Title or Artist, a handle shows the initial letter and can be dragged to jump. The app reopens on the tab, the sort and its direction the user last used. | Must | 1 | Provisional (A-7) | A test set of Chinese and English titles sorts in the expected pinyin and alphabetical order, and Simplified and Traditional forms of the same character sort alike; dragging the handle to "C" shows the first title starting with C (or pinyin c); reversing the order shows the same list backwards, a changed sort field returns to that field's natural direction; after closing and reopening, the same tab, sort and direction are shown. | S-1 |
| R-26 | The user can export all playlists to a file they choose and import them again, so that playlists survive corruption of the app's data, a reinstall and a move to a different device, independent of Google's backup. The user has to export while the playlists are healthy; the app does not export by itself. The file is made and read only by the app; nothing leaves the phone unless the user moves the file. | Should | 1.2 | Agreed (the user's decision, 2026-10-31); designed in Technical Design 5.7 and UX Design 4.8; built in G1 on 2026-11-06. The same file carries the song-details lookup's record of which songs it has already decided (H12), so a reinstall does not make the lookup start over | Export then import on a phone whose playlists were deleted restores the same names, order and songs, and the songs the details lookup had already decided are not looked up again (H12); a damaged or foreign file is refused with one plain message and changes nothing; songs are matched by the rule in Technical Design 5.3 (file name and size, as for R-17 and R-12); the app still declares no internet permission. | S-3 |
| R-29 | V1.1: a match the service is sure about is applied without asking; a match it is unsure about, or several equally likely ones, is held for the user, who accepts one of them or rejects them all; a song with no match is left unchanged and listed as not found. Where the "sure" line is drawn is the user's decision once the test on the 120-song album shows the numbers (section 15). | Should | 1.1 | Provisional (A-5) | Given recorded answers from the service (a sure match, an unsure one, two equal ones, none): the first is applied and written (R-27), the second and third are held and nothing is changed until the user accepts or rejects, the fourth is listed as not found; a rejected song is not looked up again unless the user asks. The wrong-automatic-match count on the 120 songs is reported against the target the user sets. | S-7 |
| R-30 | V1.1: the user can change the title, artist and album of any song by hand. The change is saved the way R-27 saves metadata (in the file's tags when allowed, otherwise in the app) and is never replaced by a later lookup. | Should | 1.1 | Provisional (A-9) | On copies of the user's files: after an edit the new text shows in the lists, in search, in Now Playing and in a second player, Chinese text included; it is still there after a rescan and after a lookup pass; if the file cannot be changed the edit still shows in the app and the user is told it is not in the file. | S-7 |
| R-31 | V1.1: when the app changes a song's title, artist or album (by lookup or by hand), it keeps the values the song had before, and the user can undo the change; undoing restores them in the file as well. | Should | 1.1 | Provisional (A-9) | Change a song, undo it: the title, artist and album are the earlier ones in the app and in the file's tags; if the earlier values cannot be restored (the file changed meanwhile, permission withdrawn) the user is told and nothing else changes. How many changes back is kept is for the UX design (section 15). | S-7 |
| R-32 | V1.1: an optional setting, off by default, "Also replace details that are already filled in". While it is on, songs that already have all three details are looked up too, and when the service is sure (R-29) its title, artist and album replace the song's own where they differ; a match it is unsure about is held for the user, and "Use this" then replaces as well. A song the user edited by hand (R-30) is never replaced. Every replacement keeps the earlier values and can be undone (R-31). Turning the setting off stops replacing but undoes nothing. | Should | 1.1 | Agreed | With a fake service (the file's tags are checked with the tag writer on copies of the user's files, as for R-27): setting off, a song with all three details is not looked up and nothing changes; setting on, a sure match with different text replaces the song's own (in the app and in the file's tags), the earlier values are in "Recently filled in" and Undo restores them; a hand-edited song is skipped; an unsure match is held and "Use this" replaces; a match with the same text changes nothing and the song is not looked up again; a field the match has no value for is left alone; turning the setting off afterwards leaves the replaced text as it is. | S-7 |
| R-27 | V1.1: metadata the app fills in (R-19) is saved as part of the song, in the MP3's own tags, so that other apps, the car and Bluetooth show it too. Only the tags change: the music itself is not altered and no file is moved or deleted. Android asks the user's permission to change the files (as few dialogs as Android allows for a batch of songs, A-9); if the user declines, or a file cannot be changed, the metadata is kept in the app's own storage and shown in the app, and the user is told in plain words that it is not in the file (the wording is for the UX design). The app does not keep retrying a write that failed; the user can ask again. A write that fails or is interrupted leaves the file as it was. | Should | 1.1 | Provisional (A-9) | On copies of the user's files: after a write the song plays the same, its length and audio are unchanged, the new title, artist and album show in the app and in a second player, and Chinese text is not garbled; a write stopped halfway (killed app, full storage) leaves the original file intact; declining the permission leaves the files untouched and the app still shows the metadata. | S-7 |
| R-28 | V1.1: a setting "Only look up on Wi-Fi". While it is on, the app does no lookup over mobile data; lookups wait for Wi-Fi and carry on when it returns (a wait for Wi-Fi is not a retry of a refused call: R-19 still applies). It is on by default (the user's decision, 2026-11-03). The first pass over the library may run in the background, for example while charging. | Should | 1.1 | Agreed | With the setting on and Wi-Fi off, no request is sent and the lookup resumes by itself when Wi-Fi returns; with it off, lookups run on mobile data too; a new install has it on. | S-7 |
| R-20 | V2: the splitter that cuts one long MP3 into songs is part of the player. Replaced by R-33 to R-49 below (the user, 2026-11-05). | Could | 2 | Withdrawn (replaced by R-33 to R-49) | See R-33 to R-49. | |
| R-33 | A song's menu offers "Split into songs" for MP3 songs of at least 6 minutes (lowered from 10 by the user on 2026-11-06), in the Library, in search results and on the album and artist screens. Now Playing has no song menu and gets no entry (the user, 2026-11-05). | Must | 2 | Agreed | The action appears on a 45-minute song and on a 6-minute one and not on a 5-minute one in those lists; it opens the split flow for that file. | S-8  |
| R-34 | The app proposes song boundaries for an MP3 of up to 120 songs (the author's files run from 5 to 120 songs, 2026-11-05). The size limit is 500 MB (the user, 2026-11-05; the largest known file is 477 MB). | Must | 2 | Agreed | On the author's largest file at most 2 proposed cuts need adjusting. A file over the size limit is refused before analysis with a plain message; a file whose gaps give more than 120 songs is refused with a message that says how many were found. | S-8  |
| R-35 | Each proposed cut is classed confident or uncertain; confident ones are accepted, uncertain ones are queued for review. Uncertain cuts the user does not check are used as found, and the confirm step says how many (agreed by the user, 2026-11-05). | Must | 2 | Agreed | Every cut is in exactly one class; the user is never required to review a confident one; the confirm step counts the unchecked uncertain cuts. | S-8  |
| R-36 | Review shows uncertain songs one at a time, with playback; the user can also step through any song. | Must | 2 | Agreed | The user can play a part of the original around each cut (what part: a design question) without anything being written, go on to the next uncertain cut, and open any cut, confident or not, by tapping its song in the list (the user, 2026-11-06: no Previous button). If the song being split is the one playing, the review takes over playback and says so. | S-8  |
| R-37 | In review the user can move a cut point and confirm it. | Must | 2 | Proposed | After a move, the written files start and end within 0.5 s of the moved cut when a frame that can start a song lies there (frames last about 26 ms), otherwise at the nearest such frame, which the review shows before the split. A moved or hand-placed cut may be as close as 5 seconds to its neighbours and to the file's ends (the 60-second rule of A-12 applies only to the app's own proposals, because interludes and skits can be short). | S-8, S-9  |
| R-38 | If no boundaries are found, or the file is a single song, the user can place cuts by hand. | Must | 2 | Proposed | On a file with no gaps the user places cuts and finishes the split. | S-9  |
| R-39 | A corrupt or unreadable file writes nothing and says so in plain words with a next step. "Corrupt" means the audio cannot be read as MP3 frames beyond the small amount of junk the command-line tool tolerates (under 1 %). | Must | 2 | Proposed | On a file of random bytes and on a file cut off in the middle of a frame a message is shown and no new files exist; a file with a little junk at its start (an ID3 tag, a few bad frames) is split. | S-8  |
| R-40 | Nothing is written until the user confirms. | Must | 2 | Proposed | Before the confirm step the folder contains no files from this run. | S-8  |
| R-41 | The new files are written next to the original, in the same folder when Android allows audio files there (R-42), named `<original name without its extension> <number>.mp3`, and the original is never changed or moved (deleting it is R-49 and only on the user's yes). The audio is copied unchanged: cuts fall on MP3 frame boundaries and nothing is re-encoded. | Must | 2 | Proposed | The original's bytes are unchanged; each new file is playable; every audio frame of the new files comes from the original in order, the first song starts at the original's first audio frame and the last ends at its last, and the only frame added is a Xing header in a song from a variable-bit-rate source (the command-line tool does the same). The number has at least two digits and as many as the song count needs (01 to 99, 001 to 120), as in the tool, so the files sort in order. A name that already exists is never overwritten and never silently renamed by Android: with the same size it counts as written, with different content the job stops before writing and says which name is in the way. | S-8  |
| R-42 | Android only lets an app add audio files under its audio folders (Music, Podcasts, Audiobooks, Recordings and the like, on the volume of the original). If the original sits elsewhere (Download, a chat app's folder, another app's folder), the app says so before the review and offers a folder named after the original inside Music instead. (Agreed by the user, 2026-11-05.) | Must | 2 | Agreed | With the original in such a folder, the user is told before spending time reviewing; with a yes, the files go to Music/<original name>/ on the same volume, and the free-space check (R-46) is made on that volume. With a no, nothing is written. | S-8  |
| R-43 | The job continues if the user leaves the app, shows progress, and notifies when done; if the notification permission is denied the result is shown when the app is opened. Long jobs use Android's foreground-service permission (agreed by the user, 2026-11-05). The job goes on behind the Library like the detail filler (the user, 2026-11-06): Back on the progress screens leaves it running, a banner on the Library shows it and brings the user back, and the notification opens the app at the job. | Must | 2 | Agreed | Switching apps does not stop it; pressing Back on the progress screen does not stop it (only Cancel does); a banner on the Library shows the job and its result; a notification (or the result on next open) says how many songs were written. | S-8  |
| R-44 | The user can cancel safely at any time. | Must | 2 | Proposed | After cancelling, no partial song file exists and the original is unchanged; songs already completed are kept (the user's decision, 2026-11-05) and the message lists how many. | S-8  |
| R-45 | After an interruption, songs already written are kept and a rerun, started from the same song's menu ("Continue the earlier split" or "Start over"), finishes the rest; the run record keeps the cuts so nothing is analysed or reviewed again. No half-written or duplicate song files result. | Must | 2 | Proposed | No file with a song's final name is ever incomplete; after a forced stop mid-write a temporary file may remain and is removed on the next run; a continued run yields each song exactly once and asks nothing again; "Start over" with names already in use follows the name rule of R-41. | S-10  |
| R-46 | Before writing, the app shows the number of songs and the free space on the volume it will write to, and refuses to start when the space is too small. | Must | 2 | Proposed | With less free space than the original's size plus a margin of 5 % (for temporary files) the split does not start and says why; if the space is used up during the writing, the job stops cleanly (R-45). | S-8  |
| R-47 | After the split, the user can remove the songs this split made, in one step ("Remove these songs"). | Should | 2 | Agreed | The app asks first ("Remove 12 songs?" with the count). It deletes only the files this run created (remembered by their media ids), whatever tags the lookup has written since, through Android's delete request. With the "Media management" access that Music Player already asks for (V1.1) Android shows no dialog of its own, so the app's question is the confirmation; without it Android adds its dialog (Android's documentation, checked 2026-11-05); the original is untouched. Both the deleting and R-49 change the standing rule of Music Player that the app never deletes music: the main documents and the source-scan test are changed in the story that builds it. | S-8  |
| R-48 | The new songs appear in the Library after the next refresh, and while "Fill in missing details" is on they are looked up like any song with no title, artist or album (R-19, which this feature depends on). With the lookup off they stay named `<original name> <number>`. | Must | 2 | Proposed | After a split and a refresh the new songs are listed in the Library; with a fake lookup they receive names; with the lookup off nothing is sent. | S-8  |
| R-49 | After a successful split the app asks whether to delete the original, with "Keep" preselected. A yes goes through Android's delete request (plus Android's own dialog when Media management access is not granted); nothing is deleted without the user's explicit yes. | Must | 2 | Agreed | After a split the question appears once; "Keep" leaves the original as it was; "Delete" removes the original and the new songs remain; a refusal in Android's dialog leaves everything. | S-8  |
| R-50 | The user can delete songs from the phone inside the app: "Delete from phone" in the ⋮ menu of a song, and "Delete" in the bar when several songs are selected. The app asks first ("Delete “Rain” from this phone?", or the count for several); a yes goes through Android's delete request (plus Android's own dialog when Media management access is not granted). The songs also leave every playlist. This replaces collapsing the same song twice (tried and removed on 2026-11-06): duplicates stay listed and the user removes the copy they do not want. | Must | 1 | Agreed (the user, 2026-11-06) | A song deleted from its menu or a selection is gone from the Library, search, albums, artists, the car and every playlist after the next refresh, and its file is gone from the phone; "Cancel" or a refusal in Android's dialog changes nothing; Android Auto and the widget do not show it; the count in the message is right. | S-11 |
| R-51 | An optional setting in Song details, "Only look up new songs", off by default: when on, the details lookup (R-19, including R-32) skips every song that was added to the phone before the setting was turned on; only songs added after it are looked up. Turning it off includes the older songs again; turning it on again starts a new period. | Must | 1.1 | Agreed (the user, 2026-11-06) | With the setting on, songs added before it was turned on are never sent and never changed, even when they lack a title, artist or album or "Also replace" is on; a song added later is looked up as usual; the "left to check" count leaves the older songs out. | S-7 |

## 7. Non-functional requirements

| ID | Category | Requirement | Status | How verified |
|---|---|---|---|---|
| NFR-1 | Performance | From opening the app to a known song playing: under 5 seconds (the user's number), 2,000 songs, the test phone. Measure (approved by the user): from a cold start of the app to audio playing, after one tap on Play for the resumed song, or one tap on a song that is visible on the first screen; typing a search or scrolling is not counted. Also: a tapped song starts within 1 second; search results appear within 1 second; next and previous respond within 0.5 second. | Agreed | Timed on the test phone and reported, with a 2,000-song test library. |
| NFR-2 | Scale and data volume | Designed for up to 2,000 songs (the user's 500 to 2,000). It still works at 5,000, perhaps slower. Playlists: up to 100, each up to 500 songs. | Agreed | Test library of 2,000 and of 5,000 songs; a 500-song playlist. |
| NFR-3 | Reliability | A song that cannot be played is skipped (R-12). Playback with the screen off runs for 2 hours of mixed songs without an unexpected stop. Playlists and play state are saved at once on every change, so killing the app loses nothing. | Agreed | 2-hour run on the test phone with a Bluetooth headset; kill test; backup and restore test. |
| NFR-4 | Security and privacy | No internet permission in V1 (R-18); V1.1 declares it only for the lookup (R-19), and what is sent is the fingerprint and the song's length. No accounts, ads or analytics. No song title, artist, file name or library content in logs. The library is not shared with other apps except through Android's media controls and Android Auto. | Agreed | Automated check of the declared permissions (V1: no internet; V1.1: the lookup is the only user of it, and a test shows no request while the feature is off); log-content check; review. |
| NFR-5 | Accessibility | TalkBack labels on every control; layouts work at the largest system font size; no meaning by color alone; text contrast at least 4.5 to 1; touch targets at least 48 dp. | Agreed | Checked per screen on the emulator and phone; automated checks where possible. |
| NFR-6 | UI quality | Calm and minimal style; consistent across screens; every screen has designed empty, loading and error states: no music found, permission refused, empty playlist, no search results, "Can't play". | Agreed | UX review of each built screen. |
| NFR-7 | Cost | Free app, no ads. Only the one-time Play developer fee. Free and open-source tools and libraries only; every new dependency or service needs a cost check and the user's yes. | Agreed | Review of dependencies before each release. |
| NFR-8 | Compatibility | Android 13 (API 33) and newer, phones only (no tablet layout). Android Auto over USB and wireless. English screens, with all text kept ready for translation later. | Agreed | Tested on the test phone and on emulators; Android Auto on the user's car and Google's test tool. |
| NFR-9 | Operability | Updates through Google Play. No remote crash reports in V1 (they need internet). Problems are found from the user's reports, the "Copy diagnostics" text (R-21) and the phone's own system logs, none of which contain song content. | Proposed | Review; log-content check. |
| NFR-10 | Battery | Playback with the screen off must not drain the battery more than other music players. Under 5 % of the battery per hour on the test phone. | Agreed | Android battery statistics on the test phone, reported. |
| NFR-11 | Splitter (V2) | Performance: no hard time limit; progress is always visible; the time to the first review prompt and to finish writing are measured on the largest file and reported. Scale: up to 120 songs per file and 500 MB; the output needs free space about equal to the original. Reliability: the original is core data, failure to read it fails loudly with a typed error and a plain message (R-39), and it is never modified. Privacy: splitting works fully offline and sends nothing anywhere; song titles, file names and audio stay out of logs; the one new permission is the foreground-service type for the long job (R-43). Accessibility, UI quality, cost and compatibility follow NFR-5 to NFR-8. | Agreed | Timed run on the user's phone; tests with a corrupt file, a killed process and a byte-for-byte check of the original; manifest test; log scan; a run with the network off. |

## 8. Platform considerations (mobile)

- **Supported OS versions and device sizes:** Android 13 and newer, phones only (NFR-8).
- **Offline:** Everything works offline; the app has no network access in V1. V1.1 uses the internet only for the metadata lookup (R-19) and can be limited to Wi-Fi (R-28).
- **Battery, data usage and background limits:** Playback must keep running with the screen off under Android's background limits (NFR-3, NFR-10). Data usage is zero in V1; in V1.1 it is the fingerprints of the songs looked up (small) and the answers.
- **Permissions requested:** Reading audio on the phone (R-2). Notifications are optional (S-4). Nothing else in V1. V1.1 adds internet access (R-19) and Android's permission to change the tags of the user's files (R-27, asked when needed, with the app still usable if declined).
- **Background work (V2):** the splitter's job runs as a foreground service of the data-processing type with a notification (R-43; `FOREGROUND_SERVICE_DATA_SYNC`), and continues behind the Library.
- **Background work (V1.1):** the lookup runs in the background under Android's limits; how long a first pass over 2,000 songs may take and when it may run is an open question (section 15).
- **App store:** Google Play. Needs the store's review, and rules for media apps on Android Auto (A-3). Release cadence: when the user decides.
- **Notifications, interruptions:** Media notification and lock-screen controls (R-9); calls and other apps' audio pause playback and it resumes (R-9). Opening an MP3 from the file manager starts the app (R-15).

## 9. Data

| Data | Source | Sensitivity | Stored where | Retention | Rebuild if lost |
|---|---|---|---|---|---|
| Music files | Already on the phone | Personal property | Phone storage (Android's own) | Not managed by the app; never moved or deleted except by the user's own delete (R-50); in V1.1 only their tags are changed, with the user's permission (R-27) | Not applicable |
| Song metadata and covers | Tags inside the files, read through Android's media database | Low | Android's media database | Same as the files | Rescan |
| Playlists | Created by the user | Personal | The app's own storage | Until deleted | Android backup (R-17) |
| Playback state (last song, position, queue, shuffle, repeat) | The app | Low | The app's own storage | Until replaced | Starts empty; not critical |
| Metadata found by the lookup (V1.1) | The lookup service and the user's review | Low | In the file's tags (R-27); the app's own storage when the file cannot be changed or until it is written, and for the user's accept or reject decisions | Until changed | Run the lookup again (the user's earlier accept and reject decisions are lost if the app's storage is lost) |
| New song files from a split (V2) | Created by the app | The user's library | The original's folder (R-41, R-42) | Until the user deletes them | Rerun the split |
| Split run records (V2) | The splitter | Low: file names and cut times, no audio, no tags | The app's own storage, `split-runs/` | 30 days, or until the user leaves the Done screen | Not rebuilt; a lost record only loses "Continue" and "Remove these songs" |

## 10. Integrations and dependencies

| Dependency | Used for | Limits or quotas | Cost | If unavailable |
|---|---|---|---|---|
| Android's media database | Finding songs and reading tags | Lists only what Android indexes (A-1) | Free | Library is empty and says why |
| Android media session and Android Auto | Notification, lock screen, headset and car controls | Google's rules for media apps (A-3) | Free | Playback still works on the phone |
| Android backup | Restoring playlists (R-17) | Depends on the user's phone backup setting (A-4) | Free | Playlists start empty on a new phone |
| V1.1 metadata lookup: AcoustID recognises a song from its fingerprint and MusicBrainz gives the title, artist and album of the match (candidates, not yet chosen) | Filling in missing details (R-19) | Official pages read 2026-11-03: AcoustID is "provided for free for non-commercial use only", at most 3 requests per second, and needs an application key (free to register); MusicBrainz about 1 request per second per IP and a User-Agent with contact information on every request. Not yet read: their data licences (non-commercial use is settled: personal and family use, decision 2026-11-03) | Must be free | The app works as in V1; the lookup waits and says some songs are waiting |

## 11. Constraints and assumptions

**Constraints:**
- Free and open-source tools only; no paid accounts other than the one-time Google Play developer fee, which was accepted earlier (NFR-7).
- Plays only MP3 files stored on the phone; no streaming.
- No internet access in V1 (R-18). V1.1 uses it only for the metadata lookup (R-19).
- The app never moves or deletes the user's music files on its own (the user's own delete, R-50, and the splitter's, R-47 and R-49, go through the app's question and Android's delete request), and in V1 it never modifies them; V1.1 changes only their tags, with the user's permission (R-27, the user's decision of 2026-11-03). The splitter (V2) makes new song files next to the original and, only after the app's own question to the user is answered yes, asks Android to delete the songs it made or the original (decision of 2026-11-05; R-47 and R-49).
- Android 13 and newer, phones.

**Assumptions:**
- A-1: Android's media database lists all of the user's MP3 files, including those in the Download folder. Earlier reading of the documentation said some folders may not be listed. Check on the test phone before the design is final.
- A-2: Chinese tags display correctly through Android's media database on the user's files. Check on the user's real files before the library screens are designed.
- A-3: A music app can be built for Android Auto with the tools Google provides, can start playing the last song when the phone connects, can answer spoken play requests, and can be published on Google Play for free with no special approval beyond its review. Check in Google's current documentation before the design is final.
- A-4: Android's automatic backup is available on the user's phone and can restore the playlists, and songs can be matched again on a new phone. Check in the design stage.
- A-5: A metadata service that is free for the developer recognises songs from their fingerprint with usable coverage of Chinese music, and a free app with no ads counts as non-commercial for it (settled by the user's decision of 2026-11-03: the app is for the user and at most family, so it is non-commercial; no request to the service). Check by testing on the 120-song album before V1.1 starts, after reading the services' data licences.
- A-9: Android lets the app change the tags of the user's MP3 files (including files other apps put there) after one permission from the user, the file is still indexed and plays afterwards, and Chinese text survives. Check in the design stage on the test phone and an emulator, on copies.
- A-11 (V2): Albums and compilations ripped as one file have detectable gaps between tracks. The command-line tool has confirmed this on the user's files.
- A-12 (V2): Every song lasts longer than one minute, so two cuts closer than 60 seconds can be rejected (the user, 2026-10-17).
- A-13 (V2): The app can create audio files in the folder of the original when that is under an audio folder of the media index (R-42 covers the other cases; checked on the phone with the user's files).
- A-14 (V2): A phone has enough memory and space for a 500 MB file; the cutter streams and does not load the file whole.
- A-10: The phone can make the fingerprints of up to 2,000 songs in the background in an acceptable time and with little battery use, and the fingerprint matches what the service expects. Check in the design stage on the test phone.
- A-7: A free way to turn Chinese characters into Hanyu Pinyin on the phone, without internet, exists, and its licence allows it in this app. Check in the design stage.
- A-6: The user's files contain lyrics (stored in the file or as `.lrc` files), or a good enough way to generate them exists. Check on the user's files before V1.1 starts; unknown today.

## 12. Phasing

| Phase | What's usable at the end | Requirements included | Done when |
|---|---|---|---|
| 1 (V1) | The whole player, on the phone and in the car | R-1 to R-18, R-21, R-22, R-24, R-25 | The user uses it daily for a week in place of YouTube Music for stored music, including at least one drive with Android Auto (if R-16 is released), with no unexpected stops. |
| 1.1 (V1.1) | Missing title, artist and album filled in automatically and saved in the songs, and lyrics | R-19, R-23, R-27 to R-32, R-51 | The measured results on the 120-song album are reported and the user accepts them; the written tags show in a second player; lyrics show for the songs that have them. |
| 1.2 (V1.2, built early in G1 on 2026-11-06) | Playlist export and import | R-26 | The user exports, deletes and re-imports the playlists and they match; also tried on a second phone or after a reinstall. |
| 2 (V2) | The splitter inside the player | R-33 to R-49 | S-8 passes on the user's largest file with at most 2 cuts adjusted, plus the failure cases of S-8. It depends on the V1.1 lookup for naming the new songs (R-19). |

## 13. Out of scope

- Streaming, recommendations, autoplay, accounts, ads, analytics.
- Audio formats other than MP3.
- Tablet layouts.
- An equalizer or sound effects; pinyin search (the user: not needed).
- Replacing existing tags by the lookup unless the user turns on R-32; lyrics editing; album covers written into files.
- Splitter backlog (not scheduled): trim silence at song edges (real gaps are 3 to 10 s, so with one cut in the middle each song carries up to about 5 s of silence at one end; a later option could keep about 0.5 s at each edge, which would change R-37, the review screen and the data model).
- Planned later: V1.1 metadata lookup and editing (R-19, R-27 to R-31) and lyrics (R-23); V2 splitter; translations of the app's text.

## 14. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Chinese tags are garbled through Android's media database (A-2) | Medium | High (G-3) | Test on the user's files first; design a fallback if needed. |
| Full Android Auto makes V1 much larger or hits Google's rules (A-3) | Medium | High | Build the phone player first as an internal milestone; check the rules early; if R-16 or R-17 proves unworkable, V1 is released without it (the user's decision). |
| Some MP3s are not in Android's media database (A-1) | Medium | Medium | Test on the test phone; the design decides the fix. |
| Playlists cannot be matched to songs on a new phone (A-4) | Medium | Medium | Decide in the design how a song is identified. |
| Playback stops in the background on the test phone or other phones | Low | High | 2-hour test (NFR-3); follow Android's guidance for media apps. |
| No free service recognises enough of the user's Chinese songs (A-5); file names cannot be used as a fallback | Medium | High for V1.1 (its only way to identify a song) | Test a sample of songs before V1.1 is built; V1.1 is separable. |
| Changing tags damages or loses a song file (R-27) | Low | High | Write to a temporary copy, check it, then replace; tests on copies; the user's permission first; the app keeps the details if a write fails. |
| The splitter's gap finder behaves differently on the phone than the PC tool on some files (V2) | Medium | More cuts to adjust | Ported to Kotlin and compared with the tool on the user's files (the "Split check" debug screen). |
| Android refuses to create audio files in the original's folder (V2) | Medium | The flow fails late | Checked before the review; a folder named after the original inside Music is offered (R-42). |
| Long splitting jobs are stopped by the system in the background (V2) | Medium | Half-finished runs | A rerun finishes the rest (R-45); the job is a foreground service. |

## 15. Open questions

| Question | Owner | Needed by |
|---|---|---|
| V1.1 lyrics: where do the words come from (text stored in the files, `.lrc` files next to them, or generated by the phone from the audio)? Is generated lyrics wanted if its quality turns out to be poor? | User | V1.1 start |
| V1.1: what share of songs found automatically is good enough, and how many wrongly applied matches are tolerable? (Suggestion: a wrong automatic match is worse than a held one, so the "sure" threshold is strict; for example at most 1 wrong in 100 applied.) | User | V1.1 start |
| How is a song identified so playlists survive a new phone (A-4)? | Design doc | Design |
| V1.1: how long may the first pass over 2,000 songs take (it runs in the background, for example while charging), and how many changes back does Undo keep for a song? | User | V1.1 start |
| V1.1: how is the fingerprint made on the phone (the service's own library is C++ and not made for Android) and how are tags written safely? | Design doc | V1.1 start |
| Google Play rules for Android Auto media apps and for audio access: confirm. | Design doc | Before design is final |
| V1.1 lookup service: the official terms of use are read (section 10); still to read: their data licences (non-commercial use is settled: personal and family use, decision 2026-11-03). | Design doc | V1.1 start |

## 16. Decision log

| Date | Decision | Reason |
|---|---|---|
| 2026-10-25 | The product is a simple offline music player; the splitter moves to V2 inside the player; automatic metadata is V1.1. | The user's choice: YouTube Music has too many features and treats local files as second-class. |
| 2026-10-25 | The player plays only files on the phone. | The user's choice. |
| 2026-10-25 | Non-goals: streaming, recommendations and autoplay, accounts, ads and tracking. | The user's choices. |
| 2026-10-25 | Main user: The user, daily, on the test phone; library 500 to 2,000 songs. | The user's answers. |
| 2026-10-25 | Success numbers: a song playing within 5 seconds of opening the app. | The user's answer. |
| 2026-10-25 | Full Android Auto (browse and now playing) is in V1. | The user's choice. It reverses the earlier assumption that Android Auto is out of scope. |
| 2026-10-25 | Resume the last played song, position and playlist. | The user's choice. |
| 2026-10-25 | Look and feel: calm and minimal. | The user's choice. |
| 2026-10-25 | A song that cannot be played is skipped silently, and marked "Can't play" in the library. | The user chose the silent skip; the marking was Claude's suggestion, accepted by the user. |
| 2026-10-25 | Permission refused: explain, with "Try again" and "Open Settings"; no folder-picker fallback. | The user's choice. |
| 2026-10-25 | Playlists must survive a reinstall or a new phone, restored automatically. | The user's choice. |
| 2026-10-25 | The app is free with no ads. | The user's choice. |
| 2026-10-25 | MP3 only. | The user's choice. |
| 2026-10-25 | English screens first; translations later. | The user's choice. |
| 2026-10-25 | TalkBack labels, large-font layouts and no meaning by color alone. | The user accepted Claude's suggestion. |
| 2026-10-25 | Android 13 and newer, phones only. | The user's choice. |
| 2026-10-25 | No internet permission in V1, and no crash reports. | The user's choice. |
| 2026-10-25 | Scenario S-1 as drafted (browse by songs, albums, artists and playlists; search; shuffle and repeat; notification, lock-screen and headset controls; resume) was confirmed by the user ("Sounds good now"). | The user's answer. |
| 2026-10-25 | Cost: the one-time Google Play developer fee is the only paid item. | Carried over from the earlier requirements, where the user accepted it. |
| 2026-10-25 | Playback stops silently when no song can be played; this deliberately overrides the working agreement's "fail loudly" rule for core data. | The user's choice of silent skip. |
| 2026-10-25 | **Drafted by Claude from the interview, awaiting the user's review:** the detailed behaviour of shuffle (every song once) and repeat; search matching fields; playlist operations (rename, reorder, multi-select); the queue view and removing from it; the Android Auto shuffle and repeat controls; placeholders for missing tags; and the "phone disconnects" behaviour. | The user's answers gave the features, not these details. |
| 2026-10-25 | Answers to the open questions: the last played song starts by itself when the phone connects to the car; voice requests on Android Auto are in; playback pauses when headphones are unplugged; search matches Simplified and Traditional Chinese; a deleted song stays in playlists, marked "Can't play"; light or dark follows the phone; the sleep timer offers 15, 30, 45 and 60 minutes and lets the current song finish (superseded on 2026-11-02, see the row of that date); the app opens on the Library only; "Copy diagnostics" is in settings. | The user's answers (voice commands, headphone pause and diagnostics were Claude's suggestions; the user said "Sure"). |
| 2026-10-25 | The library updates automatically when the app is reopened or returns to the front, and also has a manual refresh action. | The user's choice. |
| 2026-11-01 | The printed words "Shuffle on" and "Repeat one" under the icons are dropped; the on-state is shown by a ring behind the icon (and the "1" icon for repeat one) and is spoken by a screen reader (R-8). | The user's decision, after trying the player on the phone. |
| 2026-10-25 | If Android Auto (R-16) or the backup (R-17) cannot be made to work, V1 is released without it. | The user's choice. |
| 2026-10-25 | The proposed numbers are approved: search and tap speeds, 5-second resume tolerance, 2-hour playback test, 5 % battery per hour, contrast 4.5 to 1 and 48 dp touch targets, playlist limits. The silent stop when nothing can be played, a deleted song staying in playlists marked "Can't play", and the file-manager failure message are confirmed. | The user's answers ("Yes", "Keep them", "Sure"). |
| 2026-10-25 | New songs are shown through a "Recently added" sort and a one-time "N new songs found" message with a Show button (R-22). | The user accepted Claude's suggestion. |
| 2026-10-25 | Lyrics move to V1.1. No equalizer or sound effects. The home-screen widget is in V1. No pinyin search. | The user's choices. |
| 2026-10-25 | A playlist and the current queue are separate: "Play" on a playlist never changes the shuffle setting; shuffle, "Play next" and "Add to queue" change the queue only. | The user's decision, answering the UX question about Play and Shuffle. |
| 2026-10-25 | Chinese titles sort by Hanyu Pinyin (the mainland standard); a fast-scroll handle is included; the Library reopens on the last tab and sort (R-25). Search stays without pinyin. | The user's answers. |
| 2026-10-25 | The 8-second messages are right; tapping a "Can't play" song fails silently; a file opened from the file manager replaces the remembered last played song. | The user's answers. |
| 2026-10-25 | Working name of the app is "Music Player". | The user's suggestion; availability on Google Play not yet checked. |
| 2026-10-25 | Sleep timer, Play next / Add to queue, and opening an MP3 from the file manager are in V1. Favorites is not. | The user's picks from Claude's suggestions. |
| 2026-11-02 | The sleep timer pauses playback when its time runs out, in the middle of a song if need be; it does not wait for the song to finish. "End of song" is a separate choice. No explanatory text on the sheet: it should be obvious. This replaces the 2026-10-25 wording "lets the current song finish". | The user's decision after trying the timer on the phone ("a 1 minute timer means when the timer runs out, the music player runs out"). |
| 2026-11-03 | Voice in V1 is the car's assistant only (R-16); "play ..." said to the assistant on the phone itself is not in V1. | Claude's scoping while building E3, awaiting the user's yes. |
| 2026-11-03 | R-25 now says the sort order can be reversed, each field starting in its natural direction, and the direction is remembered. | The user asked for it on 2026-11-01 (story A6); the acceptance text was updated with it. |
| 2026-10-26 | The first scan after install shows no "N new songs found" message (R-22). | The user's answer during the technical design (5.1). |

| 2026-10-30 | The last played song starts on connect when Android Auto allows it (R-16, S-2); the app cannot force it. | The user's answer during the technical design (5.5). |
| 2026-10-30 | Android Auto shows four browse tabs, so the car has no Resume entry; the restored last song is in Now Playing (R-16, S-2). A song is in a playlist only once (R-10). | The user's answers during the technical design (5.5, 5.3). |
| 2026-10-31 | Export and import of playlists to a file is a post-V1 feature (R-26, V1.2), a whole feature rather than a patch. V1 keeps the corrupt-playlists message only. | The user's decision during the stories review (a local backup copy with a restore button was proposed and not chosen). |
| 2026-11-03 | V1.1 automatic metadata (R-19, new R-27 to R-31): the song is recognised only by its audio fingerprint, never by its file name; the lookup is automatic, sure matches are applied and only unsure ones go to the user; the metadata is saved in the MP3's tags when possible (this changes the V1 rule that files are never modified; the V1.1 exception is tags only, with the user's permission); an option limits lookups to Wi-Fi; everything must be free. The earlier idea of suggesting from the file name was dropped. | The user's answers to Claude's four questions: file names have too many formats; save the metadata with the song; accept the high-confidence ones and review the rest; a Wi-Fi-only option. Agreed by the user on 2026-11-03. |
| 2026-11-03 | Answers to the V1.1 questions: the lookup fills only missing fields and never replaces a tag (reversed on 2026-11-05 for users who turn on R-32); the user can undo any change the app makes (R-31); the user can edit title, artist and album by hand (R-30, new: not in V1); "Only on Wi-Fi" is on by default and the first pass may run in the background. | The user's answers. The 'sure' line, the length of the first pass, the depth of Undo and the use of AcoustID (third-party service) remain open. |
| 2026-11-03 | The V1.1 requirements (R-19, R-27 to R-31) are agreed. The app is for the user and at most family, so AcoustID's non-commercial terms are met and no request to the service is made. | The user's decision. |
| 2026-11-03 | The app is renamed from "Song Player" to "Music Player" (screens, car, widget, documents). The package name, code identifiers, repository and folders are not renamed. | The user's decision. The Google Play availability of the name is still unchecked and the name is generic. |
| 2026-11-05 | When a sure match lists several albums, any of them is fine: the app writes the first Album the service lists (the user's answer after the 5-song coverage check). R-29 is unchanged: "unsure" is about the song, not the album. | The user. |
| 2026-11-05 | After using the lookup on the phone, the user asks for an option to overwrite incorrect details that are already filled in (R-32): one setting, off by default; a sure match replaces, an unsure one goes to Review; hand-edited songs are never replaced. This reverses the 2026-11-03 rule that the lookup never replaces a tag, for users who turn it on. | Per-song "Look up again and replace" only: more careful but not what the user asked for. |
| 2026-11-06 | Artists lists each artist once: a song tagged with several names separated by a comma (",", full-width "，", "、") or a semicolon is listed under each of them, and the same artist in another case or in Traditional and Simplified Chinese is one artist. The combined name is no longer an entry. "&", "/" and "feat." are not separators, because they occur inside real names; a band whose name has a comma ("Earth, Wind & Fire") is split and is the known limit. | The user, after seeing songs with several artists in one tag listed under the combined name only, and spacing variants ("A,B" and "A, B") apart. Runner-up: treat the whole tag as one name (the old rule: simple, but a song could not be found under each of its artists). |
| 2026-10-16 | V2 splitter: Output is one MP3 per song; unidentified songs are named `<original name> <number>`. | The user's choice. |
| 2026-10-16 | V2 splitter: Confident cuts are accepted automatically, uncertain ones reviewed one at a time, any song can be stepped through. | The user does not want to enter 100 cut points. |
| 2026-10-16 | V2 splitter: Corrupt file: write nothing. Single song or no boundaries: offer manual cut points. | The user's choice. |
| 2026-10-16 | V2 splitter: Interruption: keep what was written, redo the rest; no half-written files, no duplicates. | The user's choice, with safety added by the writer. |
| 2026-10-17 | V2 splitter: The audio is copied unchanged: cuts at MP3 frame boundaries, no re-encoding; cut points accurate to about 26 ms. | The user's choice. |
| 2026-10-17 | V2 splitter: One cut point per boundary, in the middle of the gap; trimming silence goes to the backlog. | The user's choice. |
| 2026-10-19 | V2 splitter: R-45 wording: no file with a song's final name is ever incomplete; a temporary file may remain after a forced stop and is removed on the next run. | Android storage gives no atomic rename guarantee. |
| 2026-11-05 | V2 splitter: Splitting is an action in Music Player ("Split into songs" on a song's menu), not a separate app; the old file list, own player and Phase 2 recognition are dropped. | The user's choice. |
| 2026-11-05 | V2 splitter: New files are named `<original name> <number>` with no tags; the V1.1 lookup ("Fill in missing details") names them afterwards. | The user's choice; no new naming code, no guessing from file names. |
| 2026-11-05 | V2 splitter: New files go in the same folder as the original; the original is kept and never deleted by the app. | The user's choice. |
| 2026-11-05 | V2 splitter: R-42 (a folder named after the original inside Music when the original's folder cannot take audio files) is accepted. | The user's yes. |
| 2026-11-05 | V2 splitter: A foreground-service permission is acceptable for long jobs (R-43). | The user's yes. |
| 2026-11-05 | V2 splitter: After a cancel the finished songs are kept (R-44). | The user's yes. |
| 2026-11-05 | V2 splitter: After a successful split the app asks whether to delete the original (R-49); the default is Keep and Android's own confirmation is required. This replaces "the app never deletes the original". | The user: "prompt to delete". |
| 2026-11-05 | V2 splitter: "Remove these songs" (R-47) is wanted; it removes only the files the run created, whatever tags were written since, through Android's confirmation dialog. | The user's "sure". |
| 2026-11-05 | V2 splitter: The author's files run from 5 to 120 songs and all worked well with the command-line tool; the cap is 120 songs. | The user. |
| 2026-11-05 | V2 splitter: Now Playing gets no "Split into songs" entry; R-33 covers the lists and the album and artist screens. | The user: "sure". |
| 2026-11-05 | V2 splitter: Deleting always starts with the app's own question; Android adds its dialog only when Media management access is not granted (checked in Android's documentation). | Verified: with that access `createDeleteRequest` shows no dialog, and Music Player already asks for the access. |
| 2026-11-05 | V2 splitter: Uncertain cuts the user does not check are used as found, and the confirm step counts them (R-35). | The user: "sure". |
| 2026-11-05 | V2 splitter: The size limit stays 500 MB (the largest known file is 477 MB). | The user: "500 MB is good enough". |
| 2026-11-06 | V2 splitter: The splitting job is a background task like the detail filler: Back on "Finding the songs" and "Writing" leaves it running (Cancel stops it), a banner on the Library shows it and its result, and the notification opens the app at the job (R-43). A result of the writing cannot be closed from the banner, because the screen it opens holds the questions about the original and the songs (R-47, R-49). | The user. Runner-up: a full-screen progress that Back cancels (a long analysis would make the player unusable); a Settings entry for the status was proposed and is not built (the user, 2026-11-06). |
| 2026-11-06 | The same song twice is no longer collapsed in the lists (R-50 as first written, story A9, removed before it was pushed). The user deletes unwanted songs in the app instead (R-50, story A9): "Delete from phone" in the song menu and in the selection bar, the app asks first, Android's delete request does the deleting, and the song also leaves every playlist. | The user: "Instead of collapsing duplicates and hiding them, give me the ability to delete songs in app." Runner-ups: Android's trash for 30 days (restorable, but the app has no restore screen and the files linger); leaving deleted songs in playlists marked "Can't play" (R-12 for vanished files, but here the user chose the deletion). |
| 2026-11-06 | For now the release build is for the user's own phone only (a signed APK installed by hand), not for Google Play. The store steps (AcoustID's terms for a public app, the data-safety form, a privacy policy, the `MANAGE_MEDIA` declaration) are not done and are required before any store release. | The user: "I just want a release build to use on my phone". |
| 2026-11-06 | The song-details lookup started over on every song after the user reinstalled the app (its record of decided songs is in the app's private storage). Two changes: the playlist export file also carries that record (import adds decisions for songs that have none and never changes an existing one), and an optional setting "Only look up new songs" (R-51). | The user: "add them and then also an option to only fill in details for new songs and not existing ones". Runner-up for the setting: mark every existing song as decided when it is turned on (rejected: a large write, and the older songs could not be let in again without losing real decisions); for the record: Google backup (rejected: it does not restore sideloaded installs). |
| 2026-11-05 | V2 splitter: the number in a new file's name has at least two digits and as many as the song count needs (01 to 99, 001 to 120), as the command-line tool does, so the files sort in order. | The user: "sure". |

## 17. Glossary

| Term | Meaning |
|---|---|
| Library | All MP3 files on the phone that Android lists as audio. |
| Queue | The ordered list of songs that will play; made from the list a song was tapped in, and changed by "Play next" and "Add to queue". |
| Playlist | A named, ordered list of songs made by the user; it only points to files. |
| Now Playing | The screen (on the phone or the car) that shows the current song and its controls. |
| Android Auto | Google's system that shows a phone's media app on the car screen. |
| Can't play | The mark on a song that failed to play; shown with an icon and the words "Can't play". |
| Fingerprint | A short code made from a song's sound, used to recognise the song without sending the audio itself (V1.1). |
| Provisional | A requirement that depends on an assumption in section 11 that is not yet checked. |
| Resume state | The last song, position, queue, shuffle and repeat settings that the app restores at start. |
| Cut point (V2) | The position in the long MP3 where one song ends and the next begins. |
| Confident / uncertain (V2) | The splitter's own judgment of whether a proposed cut can be accepted without the user looking. |
| Review (V2) | The one-at-a-time step where the user checks uncertain cuts. |
| Gap (V2) | A stretch of near silence between two songs, 3 to 10 seconds in the user's files. |
