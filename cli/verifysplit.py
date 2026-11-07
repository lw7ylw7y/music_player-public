"""Checks that the songs of a split are exactly the original's audio frames, in order (the first-run check of the Music Player splitter).

    py verifysplit.py "Album.mp3" "Album 01.mp3" "Album 02.mp3" ...        (or a folder: py verifysplit.py "Album.mp3" -d "C:\\Music\\Album songs")

It reads every file and writes nothing. It prints the SHA-256 of the original (compare it before and after the split: the original must not change),
then for each song its number of frames and where it starts in the original, and finally PASS or FAIL:
  PASS = the audio frames of all songs, joined in the order given, are byte for byte the original's audio frames, and no song holds anything else.
         Not audio, and left out: a Xing, Info or VBRI header frame at the start of a file, and in the original ID3 tags and a cut-off last frame.
  A song with bytes that are not whole audio frames (junk, a tag, a cut-off frame) FAILS.
Exit code 0 on PASS, 1 on FAIL or an error. It does not decode anything and needs only Python 3.
"""
import argparse
import hashlib
import mmap
import os
import re
import sys
from array import array

BITRATES = {True: [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0],
            False: [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0]}
SAMPLE_RATES = {3: [44100, 48000, 32000], 2: [22050, 24000, 16000], 0: [11025, 12000, 8000]}


class VerifyError(Exception):
    pass


def _frame_at(b, i):
    """(length, mpeg1, samples_per_frame, sample_rate) of a Layer III frame at i that fits in the file, or None."""
    n = len(b)
    if i + 4 > n or b[i] != 0xFF or (b[i + 1] & 0xE0) != 0xE0:
        return None
    ver = (b[i + 1] >> 3) & 3
    if ver == 1 or (b[i + 1] >> 1) & 3 != 1:
        return None
    bi, si = b[i + 2] >> 4, (b[i + 2] >> 2) & 3
    if bi in (0, 15) or si == 3:
        return None
    mpeg1 = ver == 3
    rate = SAMPLE_RATES[ver][si]
    length = (144 if mpeg1 else 72) * BITRATES[mpeg1][bi] * 1000 // rate + ((b[i + 2] >> 1) & 1)
    if i + length > n:
        return None          # a cut-off last frame is not a frame (the splitter does not copy it either)
    return length, mpeg1, 1152 if mpeg1 else 576, rate


def _is_header_frame(b, i, mpeg1):
    mono = (b[i + 3] >> 6) == 3
    side = (17 if mono else 32) if mpeg1 else (9 if mono else 17)
    o = i + 4 + (0 if b[i + 1] & 1 else 2) + side
    return bytes(b[o:o + 4]) in (b"Xing", b"Info") or bytes(b[i + 36:i + 40]) == b"VBRI"


class Frames:
    """The audio frames of an MP3: offsets, lengths and start times in arrays, and how many bytes were not audio frames."""

    def __init__(self, b):
        self.offset = array("Q")
        self.length = array("I")
        self.start = array("d")          # seconds from the first audio frame
        self.seconds = 0.0
        self.tags = 0                    # bytes of ID3 and ID3v1 tags: not audio
        self.header = 0                  # bytes of a Xing, Info or VBRI header frame at the start: not audio
        self.junk = 0                    # any other byte that is not part of a whole audio frame
        n = len(b)
        i = 0
        seen_first = False
        synced = None                    # (sample rate, mpeg1) of the frame before, while still in step with the stream
        while i < n:
            if b[i:i + 3] == b"ID3" and i + 10 <= n:
                size = 10 + ((b[i + 6] & 127) << 21 | (b[i + 7] & 127) << 14 | (b[i + 8] & 127) << 7 | (b[i + 9] & 127))
                self.tags += min(size, n - i)
                i += size
                continue
            if b[i:i + 3] == b"TAG" and i + 128 == n:
                self.tags += 128
                break
            h = _frame_at(b, i)
            # A real frame is followed by another frame, a tag or the end of the file.
            if h and (synced == (h[3], h[1]) or i + h[0] >= n or _frame_at(b, i + h[0]) or b[i + h[0]:i + h[0] + 3] in (b"TAG", b"ID3")):
                if not seen_first and _is_header_frame(b, i, h[1]):
                    self.header += h[0]
                else:
                    self.offset.append(i)
                    self.length.append(h[0])
                    self.start.append(self.seconds)
                    self.seconds += h[2] / h[3]
                seen_first = True
                synced = (h[3], h[1])
                i += h[0]
                continue
            synced = None
            j = b.find(b"\xff", i + 1)
            j = n if j < 0 else j
            self.junk += j - i
            i = j
        if len(self.offset) < 2:
            raise VerifyError("not an MP3 file (or too short)")

    def __len__(self):
        return len(self.offset)


def frames(b):
    return Frames(b)


def fmt(t):
    t = int(t)
    return f"{t // 3600}:{t % 3600 // 60:02d}:{t % 60:02d}" if t >= 3600 else f"{t // 60}:{t % 60:02d}"


class _Mapped:
    """A file read through the operating system's mapping, so a 500 MB original is not copied into memory."""

    def __init__(self, path):
        self.f = open(path, "rb")
        self.size = os.fstat(self.f.fileno()).st_size
        self.b = mmap.mmap(self.f.fileno(), 0, access=mmap.ACCESS_READ) if self.size else b""

    def __enter__(self):
        return self.b

    def __exit__(self, *exc):
        if self.size:
            self.b.close()
        self.f.close()


def _sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest(), os.path.getsize(path)


def verify(original, songs, say=print):
    """True when the songs, in order, are the original's audio frames from the first to the last. [songs] is a list of paths."""
    digest, size = _sha256(original)
    say(f"Original: {os.path.basename(original)}  {size} bytes  SHA-256 {digest}")
    try:
        with _Mapped(original) as ob:
            of = Frames(ob)
            if of.junk:
                say(f"  note: {of.junk} bytes of the original are not audio frames (junk or a cut-off last frame); the splitter leaves them out")
            pos = 0
            for k, path in enumerate(songs, 1):
                name = os.path.basename(path)
                try:
                    with _Mapped(path) as sb:
                        sf = Frames(sb)
                        # A Xing header frame at the start of a variable-bit-rate song is the splitter's own and is expected; nothing else is.
                        if sf.junk or sf.tags:
                            say(f"  FAIL: {name} holds {sf.junk + sf.tags} bytes that are not whole audio frames")
                            return False
                        start = pos
                        for q in range(len(sf)):
                            if pos >= len(of):
                                say(f"  FAIL: {name} has more frames than are left in the original")
                                return False
                            oo, ol = of.offset[pos], of.length[pos]
                            so, sl = sf.offset[q], sf.length[q]
                            if ol != sl or ob[oo:oo + ol] != sb[so:so + sl]:
                                say(f"  FAIL: {name} differs from the original at its frame {q + 1} (original frame {pos + 1})")
                                return False
                            pos += 1
                        say(f"  {k:3d}  {len(sf):7d} frames  {fmt(sf.seconds):>8}  starts at {fmt(of.start[start])} in the original  {name}")
                except VerifyError as e:
                    say(f"  FAIL: {name}: {e}")
                    return False
            if pos != len(of):
                say(f"  FAIL: {len(of) - pos} frames at the end of the original are in no song")
                return False
            say(f"PASS: {len(songs)} songs hold all {len(of)} audio frames of the original, in order, unchanged.")
            return True
    except VerifyError as e:
        say(f"  FAIL: {os.path.basename(original)}: {e}")
        return False


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("original")
    p.add_argument("songs", nargs="*", help="the song files in order")
    p.add_argument("-d", "--dir", help="a folder: its .mp3 files, by name, are the songs (the original is left out if it is in there)")
    args = p.parse_args(argv)
    songs = list(args.songs)
    if args.dir:
        def key(name):
            return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", name)]
        original = os.path.abspath(args.original)
        songs += [os.path.join(args.dir, f) for f in sorted(os.listdir(args.dir), key=key)
                  if f.lower().endswith(".mp3") and os.path.abspath(os.path.join(args.dir, f)) != original]
    if not songs:
        print("Give the song files, or a folder with -d.", file=sys.stderr)
        return 1
    try:
        return 0 if verify(args.original, songs) else 1
    except OSError as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
