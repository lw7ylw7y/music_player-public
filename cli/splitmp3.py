#!/usr/bin/env python3
"""Split one long MP3 into one MP3 per song, from the command line.

Songs are cut losslessly on MP3 frame boundaries (no re-encoding). Cut points
are the middle of the long silent gaps between songs. The input is only read.
Companion to the Song Editor Android app; same method as its Technical Design 5.2-5.4.
"""
import argparse
import mmap
import os
import re
import shutil
import sys
from dataclasses import dataclass

import numpy as np

HOP = 0.02            # seconds per loudness step
MIN_SONG = 60.0       # a song is at least a minute (Requirements); closer cuts cannot both be real
MIN_CANDIDATE = 0.3   # shortest quiet run considered at all
CONFIDENT_GAP = 2.0   # a confident gap is at least this long...
CONFIDENT_DEPTH = 10  # ...and within this many dB of the file's silence floor
MISSED_MIN = 480.0    # a stretch longer than this and 2.5x the median song is flagged
SCAN_LIMIT = 1 << 20  # no frame in the first MB means this is not an MP3

BITRATES = {True: [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0],
            False: [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0]}
SAMPLE_RATES = {3: [44100, 48000, 32000], 2: [22050, 24000, 16000], 0: [11025, 12000, 8000]}


class SplitError(Exception):
    """Base for errors shown to the user as one plain message."""


class NotMp3(SplitError):
    pass


class Corrupt(SplitError):
    pass


class OutputProblem(SplitError):
    pass


class BadTitles(SplitError):
    pass


# ---------------------------------------------------------------- frames

def _header(b, i, n):
    """(length, sample_rate, samples, mpeg1, bitrate_index) of a Layer III frame at i, or None."""
    if i + 4 > n or b[i] != 0xFF or (b[i + 1] & 0xE0) != 0xE0:
        return None
    ver = (b[i + 1] >> 3) & 3
    if ver == 1 or (b[i + 1] >> 1) & 3 != 1:
        return None
    bi, si = b[i + 2] >> 4, (b[i + 2] >> 2) & 3
    if bi in (0, 15) or si == 3:
        return None
    mpeg1 = ver == 3
    sr = SAMPLE_RATES[ver][si]
    length = (144 if mpeg1 else 72) * BITRATES[mpeg1][bi] * 1000 // sr + ((b[i + 2] >> 1) & 1)
    return length, sr, 1152 if mpeg1 else 576, mpeg1, bi


def _side_info(mpeg1, mono):
    return (17 if mono else 32) if mpeg1 else (9 if mono else 17)


def _id3_size(b, i):
    return 10 + ((b[i + 6] & 127) << 21 | (b[i + 7] & 127) << 14 | (b[i + 8] & 127) << 7 | (b[i + 9] & 127))


def _is_header_frame(b, i, mpeg1):
    mono = (b[i + 3] >> 6) == 3
    o = i + 4 + (0 if b[i + 1] & 1 else 2) + _side_info(mpeg1, mono)
    return bytes(b[o:o + 4]) in (b"Xing", b"Info") or bytes(b[i + 36:i + 40]) == b"VBRI"


@dataclass
class FrameIndex:
    offset: np.ndarray
    length: np.ndarray
    sample_rate: np.ndarray
    samples: np.ndarray
    bitrate_idx: np.ndarray
    main_data_begin: np.ndarray
    first: int            # index of the first audio frame (1 when frame 0 is a Xing/Info header)
    junk: int

    @property
    def start_time(self):
        return np.concatenate([[0.0], np.cumsum(self.samples / self.sample_rate)])

    @property
    def count(self):
        return len(self.offset)


def parse_frames(b):
    """Index every MPEG audio frame of Layer III in b (bytes-like). Raises NotMp3 or Corrupt."""
    n = len(b)
    if n == 0:
        raise NotMp3("The file is empty.")
    cols = ([], [], [], [], [], [])
    junk = 0
    i = _id3_size(b, 0) if bytes(b[:3]) == b"ID3" else 0
    first = 0
    synced = None  # (sample_rate, mpeg1) of the previous frame when we are still in step with the stream
    while i < n:
        if b[i] == 0x49 and bytes(b[i:i + 3]) == b"ID3" and i + 10 <= n:
            i += _id3_size(b, i)
            continue
        if b[i] == 0x54 and i + 128 == n and bytes(b[i:i + 3]) == b"TAG":
            break
        h = _header(b, i, n)
        if h and (synced == (h[1], h[3]) or i + h[0] >= n or _header(b, i + h[0], n)
                  or bytes(b[i + h[0]:i + h[0] + 3]) in (b"TAG", b"ID3")):
            length, sr, spf, mpeg1, bi = h
            if not cols[0] and _is_header_frame(b, i, mpeg1):
                first = 1
            crc = 0 if b[i + 1] & 1 else 2
            o = i + 4 + crc
            mdb = (b[o] << 1 | b[o + 1] >> 7) if mpeg1 else b[o]
            for col, v in zip(cols, (i, length, sr, spf, bi, mdb)):
                col.append(v)
            i += length
            synced = (sr, mpeg1)
            continue
        synced = None
        j = i + 1
        while j < n:
            j = b.find(b"\xff", j)
            if j < 0:
                j = n
                break
            h = _header(b, j, n)
            if h and (j + h[0] >= n or _header(b, j + h[0], n)):
                break
            j += 1
        junk += j - i
        i = j
        if not cols[0] and i > SCAN_LIMIT:
            raise NotMp3("This doesn't look like an MP3 file.")
        if junk > n // 100:
            if not cols[0]:
                raise NotMp3("This doesn't look like an MP3 file.")
            raise Corrupt("This file can't be read. It may be damaged: more than 1% of it is not valid MP3 data.")
    if len(cols[0]) - first < 2:
        raise NotMp3("This doesn't look like an MP3 file.")
    a = [np.array(c, dtype=np.int64) for c in cols]
    return FrameIndex(*a, first=first, junk=junk)


def build_xing(header4, frames, total_bytes):
    """A silent frame with a Xing header, so players show the right length of a VBR song."""
    ver = (header4[1] >> 3) & 3
    mpeg1 = ver == 3
    mono = (header4[3] >> 6) == 3
    need = 4 + _side_info(mpeg1, mono) + 16
    sri = (header4[2] >> 2) & 3
    sr = SAMPLE_RATES[ver][sri]
    for bi in range(1, 15):
        length = (144 if mpeg1 else 72) * BITRATES[mpeg1][bi] * 1000 // sr
        if length >= need:
            break
    frame = bytearray(length)
    frame[0:4] = bytes([0xFF, header4[1] | 1, bi << 4 | sri << 2, header4[3] & 0xC0])
    o = 4 + _side_info(mpeg1, mono)
    frame[o:o + 16] = b"Xing" + (3).to_bytes(4, "big") + frames.to_bytes(4, "big") + (total_bytes + length).to_bytes(4, "big")
    return bytes(frame)


# ---------------------------------------------------------------- loudness

def loudness(path, progress=None):
    """RMS in dB per 20 ms step, decoded in a stream so memory stays small."""
    import miniaudio

    class Source(miniaudio.StreamableSource):
        def __init__(self):
            self.f = open(path, "rb")
            self.total = os.path.getsize(path)

        def read(self, num_bytes):
            data = self.f.read(num_bytes)
            if progress:
                progress(self.f.tell() / self.total)
            return data

        def close(self):
            self.f.close()

    step = int(44100 * HOP)
    out = []
    with Source() as src:
        try:
            for chunk in miniaudio.stream_any(src, miniaudio.FileFormat.MP3, miniaudio.SampleFormat.SIGNED16,
                                              nchannels=1, sample_rate=44100, frames_to_read=step * 100):
                x = np.frombuffer(chunk, dtype=np.int16).astype(np.float32) / 32768.0
                m = len(x) // step
                if m:
                    rms = np.sqrt((x[:m * step].reshape(m, step) ** 2).mean(1))
                    out.append(20 * np.log10(np.maximum(rms, 1e-5)))
        except miniaudio.DecodeError as e:
            raise Corrupt("This file can't be read. It may be damaged: the decoder failed.") from e
    if not out:
        raise NotMp3("This doesn't look like an MP3 file.")
    return np.concatenate(out)


# ---------------------------------------------------------------- cuts

@dataclass
class Cut:
    time: float       # middle of the gap, seconds
    length: float     # length of the quiet run, seconds
    depth: float      # lowest level in the run, dB
    confident: bool
    reason: str
    strength: float = 0.0   # length weighted by depth; how clearly this is a song gap


def find_cuts(db, min_gap=CONFIDENT_GAP):
    """Cuts from a per-20-ms loudness array. Confident and uncertain are both returned, sorted by time."""
    n = len(db)
    if n * HOP < 2 * MIN_SONG:
        return []
    floor, med = np.percentile(db, [2, 50])
    thr = floor + 0.25 * (med - floor)
    below = np.concatenate([[False], db < thr, [False]])
    edges = np.flatnonzero(below[1:] != below[:-1])
    runs = [(a, z) for a, z in zip(edges[::2], edges[1::2]) if (z - a) * HOP >= MIN_CANDIDATE]
    cands = []
    for a, z in runs:
        t, ln = (a + z) / 2 * HOP, (z - a) * HOP
        if t < MIN_SONG or n * HOP - t < MIN_SONG:
            continue
        depth = float(db[a:z].min())
        cands.append(Cut(t, ln, depth, True, "", ln * (1 + max(0.0, thr - depth) / 20)))
    accepted = []
    rival = set()
    for c in sorted(cands, key=lambda c: c.strength, reverse=True):
        near = [a for a in accepted if abs(a.time - c.time) < MIN_SONG]
        if near:
            for a in near:
                if a.strength < 3 * c.strength and a.depth - c.depth < 20:
                    rival.add(id(a))
            continue
        accepted.append(c)
    for c in accepted:
        if id(c) in rival:
            c.confident, c.reason = False, "a similar gap within a minute"
        elif c.length < min_gap:
            c.confident, c.reason = False, "short gap"
        elif c.depth > floor + CONFIDENT_DEPTH:
            c.confident, c.reason = False, "not fully silent"
    return sorted(accepted, key=lambda c: c.time)


def select_cuts(cuts, songs):
    """Cuts to use when the number of songs is known: every confident cut, plus as many of the strongest
    uncertain ones as are needed to reach `songs`. Falls back to the confident cuts alone when the count
    cannot be reached that way, so the caller reports the mismatch."""
    confident = [c for c in cuts if c.confident]
    need = songs - 1 - len(confident)
    if need < 0 or need > len(cuts) - len(confident):
        return confident
    extra = sorted((c for c in cuts if not c.confident), key=lambda c: -c.strength)[:need]
    return sorted(confident + extra, key=lambda c: c.time)


def missed_stretches(cut_times, duration):
    """(start, end) of stretches much longer than a typical song, which may hold a missed cut."""
    bounds = [0.0] + list(cut_times) + [duration]
    lens = np.diff(bounds)
    limit = max(MISSED_MIN, 2.5 * float(np.median(lens)))
    return [(bounds[k], bounds[k + 1]) for k in range(len(lens)) if lens[k] > limit]


def snap_to_frame(fi, cut):
    """Frame to start the next song: nearest to the gap's middle with main_data_begin == 0, inside the gap."""
    start = fi.start_time
    at = lambda t: int(np.searchsorted(start, t, side="right")) - 1
    mid = min(max(at(cut.time), fi.first + 1), fi.count - 1)
    lo, hi = max(at(cut.time - cut.length / 2), fi.first + 1), min(at(cut.time + cut.length / 2), fi.count - 1)
    ok = [k for k in range(lo, hi + 1) if fi.main_data_begin[k] == 0]
    return min(ok, key=lambda k: abs(k - mid)) if ok else mid


# ---------------------------------------------------------------- output

@dataclass
class Song:
    name: str
    a: int
    z: int
    size: int          # bytes of audio copied from the source
    vbr: bool          # needs its own Xing header frame (the source's describes the whole file)


DIVIDERS = re.compile(r"[#;|/\n\r\uFF0C\u3001\uFF1B]")   # # ; | / newline, and full-width , \u3001 ;
ILLEGAL = re.compile(r'[\\:*?"<>\x00-\x1f]')


def parse_titles(text):
    """Song titles from one string. Spaces stay inside titles; empty items are ignored."""
    titles = []
    for raw in DIVIDERS.split(text):
        t = ILLEGAL.sub("", raw).strip().rstrip(". ")
        if raw.strip() and not t:
            raise BadTitles(f'The title "{raw.strip()}" has no usable characters for a file name.')
        if t:
            titles.append(t)
    return titles


def plan_songs(fi, stem, cut_frames, titles=None):
    bounds = [fi.first] + list(cut_frames) + [fi.count]
    vbr = len(set(fi.bitrate_idx[fi.first:].tolist())) > 1
    width = max(2, len(str(len(bounds) - 1)))
    if titles is not None and len(titles) != len(bounds) - 1:
        hint = " Try --include-uncertain, or check the cuts with --dry-run." if len(titles) > len(bounds) - 1 else \
            " The tool may have missed a cut; check with --dry-run."
        raise BadTitles(f"The list has {len(titles)} titles but the file splits into {len(bounds) - 1} songs.{hint}")
    songs = []
    for k in range(len(bounds) - 1):
        a, z = bounds[k], bounds[k + 1]
        body = int(fi.offset[z - 1] + fi.length[z - 1] - fi.offset[a])
        name = f"{k + 1:0{width}d} {titles[k]}.mp3" if titles else f"{stem} {k + 1:0{width}d}.mp3"
        songs.append(Song(name, a, z, body, vbr))
    return songs


def write_songs(b, fi, songs, outdir, src_path, overwrite=False, say=print):
    """Write all songs atomically. Checks everything before the first byte is written."""
    todo = []
    for s in songs:
        path = os.path.join(outdir, s.name)
        if os.path.abspath(path) == os.path.abspath(src_path):
            raise OutputProblem("The output file would replace the original. Choose another folder with -o.")
        head = build_xing(bytes(b[fi.offset[s.a]:fi.offset[s.a] + 4]), s.z - s.a, s.size) if s.vbr else b""
        total = len(head) + s.size
        if os.path.exists(path):
            if os.path.getsize(path) == total:
                say(f"  skip   {s.name} (already written)")
                continue
            if not overwrite:
                raise OutputProblem(f"{s.name} already exists in {outdir} with different content. "
                                    "Nothing was written. Use another folder, or add --overwrite.")
        todo.append((s, path, head, total))
    need = sum(t for *_, t in todo)
    os.makedirs(outdir, exist_ok=True)
    free = shutil.disk_usage(outdir).free
    if need > free:
        raise OutputProblem(f"Not enough free space: {need / 1e6:.0f} MB needed, {free / 1e6:.0f} MB free. Nothing was written.")
    for s, path, head, _ in todo:
        tmp = path + ".part"
        try:
            with open(tmp, "wb") as f:
                f.write(head)
                f.write(b[fi.offset[s.a]:fi.offset[s.z - 1] + fi.length[s.z - 1]])
            os.replace(tmp, path)
        except BaseException:
            if os.path.exists(tmp):
                os.remove(tmp)
            raise
        say(f"  wrote  {s.name}")
    return len(todo)


# ---------------------------------------------------------------- command line

def fmt(t):
    return f"{int(t // 60)}:{t % 60:04.1f}"


def run(args, say=print, err=lambda m: print(m, file=sys.stderr)):
    path = args.input
    if not os.path.isfile(path):
        raise SplitError(f"File not found: {path}")
    stem = os.path.splitext(os.path.basename(path))[0]
    titles = None
    if args.titles and args.titles_file:
        raise BadTitles("Use either --titles or --titles-file, not both.")
    if args.titles_file:
        try:
            with open(args.titles_file, encoding="utf-8-sig") as f:
                titles = parse_titles(f.read())
        except UnicodeDecodeError:
            raise BadTitles("The titles file must be saved as UTF-8.")
    elif args.titles:
        titles = parse_titles(args.titles)
    if titles is not None and not titles:
        raise BadTitles("The title list is empty.")
    outdir = args.output or os.path.join(os.path.dirname(os.path.abspath(path)), stem + " songs")
    if os.path.getsize(path) == 0:
        raise NotMp3("The file is empty.")
    with open(path, "rb") as f, mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ) as b:
        fi = parse_frames(b)
        err("Listening for gaps...")
        last = [-1]

        def progress(p):
            if int(p * 20) != last[0]:
                last[0] = int(p * 20)
                err(f"  {last[0] * 5}%")
        db = loudness(path, progress)
        duration = len(db) * HOP
        cuts = find_cuts(db, args.min_gap)
        if args.include_uncertain:
            use = list(cuts)
        elif titles:
            use = select_cuts(cuts, len(titles))
        else:
            use = [c for c in cuts if c.confident]
        say(f"{os.path.basename(path)}: {fi.count - fi.first} frames, {fmt(duration)}, "
            f"{'VBR' if len(set(fi.bitrate_idx[fi.first:].tolist())) > 1 else 'CBR'}")
        for k, c in enumerate(cuts, 1):
            tag = "confident" if c.confident else f"uncertain ({c.reason})"
            if c in use:
                mark = "" if c.confident or args.include_uncertain else f"  <- used, to reach {len(titles)} songs"
            else:
                mark = "  <- not used" if titles else "  <- not used; add --include-uncertain"
            say(f"  cut {k:>3}  at {fmt(c.time):>8}  gap {c.length:4.1f}s  {c.depth:6.1f} dB  {tag}{mark}")
        for a, z in missed_stretches([c.time for c in use], duration):
            say(f"  warning: {fmt(a)} to {fmt(z)} is much longer than a typical song; it may hold a missed cut")
        songs = plan_songs(fi, stem, [snap_to_frame(fi, c) for c in use], titles)
        say(f"{len(songs)} songs -> {outdir}")
        if titles:
            for s in songs:
                say(f"  {s.name}")
        if args.dry_run:
            say("Dry run: nothing written.")
            return 0
        n = write_songs(b, fi, songs, outdir, path, args.overwrite, say)
        say(f"Done: {n} written, {len(songs) - n} already there.")
    return 0


def main(argv=None):
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(errors="replace")
    p = argparse.ArgumentParser(description="Split one long MP3 into one MP3 per song, without re-encoding.")
    p.add_argument("input", help="the MP3 file; it is only read")
    p.add_argument("-o", "--output", help='output folder (default: "<name> songs" next to the input)')
    p.add_argument("-n", "--dry-run", action="store_true", help="show the cuts and file names, write nothing")
    p.add_argument("--include-uncertain", action="store_true", help="also cut at gaps the tool is not sure about")
    p.add_argument("--titles", metavar="LIST", help='song titles in order, separated by # ; | / (not space), '
                   'e.g. "#First song#Second song"; files are named "01 First song.mp3"')
    p.add_argument("--titles-file", metavar="FILE", help="same list read from a UTF-8 text file (newlines also separate)")
    p.add_argument("--min-gap", type=float, default=CONFIDENT_GAP, metavar="SEC",
                   help=f"shortest gap that counts as confident (default {CONFIDENT_GAP})")
    p.add_argument("--overwrite", action="store_true", help="replace song files that exist with different content")
    try:
        return run(p.parse_args(argv))
    except SplitError as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Cancelled. Finished songs are kept; no half-written files are left.", file=sys.stderr)
        return 130
    except OSError as e:
        print(f"Error: {e.strerror or e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
