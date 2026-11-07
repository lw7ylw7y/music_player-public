# splitmp3

Command-line tool that splits one long MP3 into one MP3 per song. A companion to the Music Player app, using the same method (Technical Design 5.9 in `docs/Technical Design.md`): silent gaps between songs are found, and each song is copied out losslessly on MP3 frame boundaries. Nothing is re-encoded and the original file is only read.

## Setup (Windows)

1. Install Python 3 from <https://www.python.org/downloads/> (tick "Add python.exe to PATH").
2. In this folder: `py -m pip install -r requirements.txt` (installs `numpy` and `miniaudio`, both free and open source).

## Use

```
py splitmp3.py "C:\Music\Album.mp3"
```

Songs are written to `C:\Music\Album songs\` as `Album 01.mp3`, `Album 02.mp3`, and so on.

| Option | Effect |
|---|---|
| `-n`, `--dry-run` | Show the cuts and file names; write nothing. Run this first. |
| `-o FOLDER` | Write to this folder instead. |
| `--include-uncertain` | Also cut at gaps the tool is not sure about (short, not fully silent, or a similar gap within a minute). Without it only confident cuts are used and the others are listed as "not used". |
| `--titles LIST` | Name the songs from a list, in order, e.g. `--titles "#一直很安静#寂寞在唱歌#温柔的慈悲"`. Files become `01 一直很安静.mp3`, `02 寂寞在唱歌.mp3`, and so on. Dividers: `#` `;` `\|` `/` and the full-width `，` `、` `；`. A space is not a divider, so titles can contain spaces; an ASCII comma is not one either, so titles can contain commas. A leading or doubled divider is ignored. With a list the tool knows how many songs to expect: if the confident cuts give fewer, it adds the strongest uncertain cuts (long, deep gaps first) until the count matches, and the report marks which were used and which were left out. If the count still can't be reached, nothing is written. Characters Windows does not allow in file names (`\ : * ? " < >`) are removed. |
| `--titles-file FILE` | The same list read from a UTF-8 text file; a new line also separates titles. |
| `--min-gap SEC` | Shortest gap that counts as confident (default 2). |
| `--overwrite` | Replace song files that already exist with different content. |

Exit code 0 on success, 1 on an error (printed as one plain message), 130 if cancelled with Ctrl+C.

## Checking a split (`verifysplit.py`)

```
py verifysplit.py "Album.mp3" -d "C:\Music\Album songs"
```

Reads the original and the songs (a folder with `-d`, or the files listed in order) and writes nothing. It prints the original's SHA-256 and each song's frame count and start, then **PASS** when the songs joined in order are exactly the original's audio frames, unchanged (a Xing or Info header frame and ID3 tags are not audio and are left out), else **FAIL** with the first difference. It also checks the songs the Music Player app wrote. Needs only Python 3.

## Behaviour to know

- A gap is cut in its middle, so each song can carry a few seconds of silence at one end.
- A stretch much longer than a typical song is reported as a warning; it may hold a cut the tool missed.
- Each song is written under a `.part` name and renamed when complete, so a cancel or crash leaves no half-written song. Running again skips songs already written (same name and size).
- If a name exists with different content, or there is not enough free space, nothing is written.
- A VBR source gets a new Xing header frame in each song so players show the right length; a constant-bitrate source needs none.
- Windows limits a full path to about 260 characters. Very long input names (many titles in the file name) can exceed it with ` 01.mp3` added; use `-o` with a short folder.
- Not implemented: reviewing or nudging cuts (the app does that), titles and artists, other formats.

## Tests

```
py -m unittest -v
```

Offline and fast (synthetic data). One test needs a real MP3 and is skipped unless `SPLITMP3_REAL_FILE` is set (it only reads the file).

## Verified so far

On Linux (WSL) only, not yet on Windows: the unit tests pass; on the user's three small test albums the tool finds exactly the expected cuts (4, 3 and 5), the written songs decode to the expected lengths, a VBR album's songs carry a correct Xing header, a rerun skips finished songs, and the original file is byte-identical afterwards. Not tried: the 500 MB, 100-song file.
