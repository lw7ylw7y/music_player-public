"""Offline tests of verifysplit with synthetic frames (no audio needed)."""
import os
import shutil
import tempfile
import unittest

import verifysplit as vs

FRAME = 417  # MPEG-1 Layer III, 128 kbps, 44.1 kHz, no padding
HDR = bytes([0xFF, 0xFB, 0x90, 0x00])


def frame(n):
    f = bytearray([n % 251 + 1]) * FRAME
    f[0:4] = HDR
    f[4], f[5] = n % 7, n % 5            # distinct bytes after the header, so a swapped frame is noticed
    return bytes(f)


def xing():
    f = bytearray(FRAME)
    f[0:4] = HDR
    f[36:40] = b"Xing"
    return bytes(f)


def other_header_frame(kind):
    """A header frame of another layout: VBRI, MPEG-2 mono, MPEG-2 stereo, or a CRC-protected frame (the tag sits 2 bytes later)."""
    if kind == "vbri":
        f = bytearray(FRAME); f[0:4] = HDR; f[36:40] = b"VBRI"
    elif kind == "mpeg2-mono":
        length = 72 * 64 * 1000 // 22050                # MPEG-2, 64 kbps, 22.05 kHz
        f = bytearray(length); f[0:4] = bytes([0xFF, 0xF3, 0x80, 0xC0]); f[4 + 9:4 + 9 + 4] = b"Xing"
    elif kind == "mpeg2-stereo":
        length = 72 * 64 * 1000 // 22050
        f = bytearray(length); f[0:4] = bytes([0xFF, 0xF3, 0x80, 0x00]); f[4 + 17:4 + 17 + 4] = b"Info"
    else:                                               # crc
        f = bytearray(FRAME); f[0:4] = bytes([0xFF, 0xFA, 0x90, 0x00]); f[4 + 2 + 32:4 + 2 + 32 + 4] = b"Xing"
    return bytes(f)


class VerifyTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir, ignore_errors=True)
        self.said = []

    def write(self, name, data):
        path = os.path.join(self.dir, name)
        with open(path, "wb") as f:
            f.write(data)
        return path

    def split(self, parts, header=False):
        original = self.write("o.mp3", (xing() if header else b"") + b"".join(frame(n) for n in range(sum(parts))))
        at, songs = 0, []
        for k, c in enumerate(parts, 1):
            songs.append(self.write(f"s{k}.mp3", (xing() if header else b"") + b"".join(frame(n) for n in range(at, at + c))))
            at += c
        return original, songs

    def run_verify(self, original, songs):
        return vs.verify(original, songs, say=self.said.append)

    def test_Songs_JoinedAreTheOriginal_Passes_WithAndWithoutXingHeaders(self):
        for header in (False, True):
            original, songs = self.split([40, 25, 60], header=header)
            self.assertTrue(self.run_verify(original, songs), header)
        self.assertIn("PASS", self.said[-1])

    def test_TheSplittersRealCase_NoHeaderInTheOriginal_XingFramesAtTheStartOfTheSongs(self):
        original, songs = self.split([40, 25, 60])                                   # a headerless original...
        for path in songs:
            with open(path, "rb") as f:
                data = f.read()
            self.write(os.path.basename(path), xing() + data)                       # ...and a new Xing frame in front of every song
        self.assertTrue(self.run_verify(original, songs))
        original, songs = self.split([40, 25], header=True)                          # a Xing-led original with plain songs
        for path in songs:
            with open(path, "rb") as f:
                self.write(os.path.basename(path), f.read()[FRAME:])
        self.assertTrue(self.run_verify(original, songs))

    def test_EveryKindOfHeaderFrameIsLeftOut(self):
        for kind in ("vbri", "mpeg2-mono", "mpeg2-stereo", "crc"):
            head = other_header_frame(kind)
            body = b"".join(frame(n) for n in range(30))
            original = self.write("o.mp3", head + body)
            song = self.write("s.mp3", body)
            self.assertTrue(vs.verify(original, [song], say=self.said.append), kind)
            self.assertEqual(30, len(vs.frames(open(original, "rb").read())), kind)

    def test_ACutOffLastFrameInTheOriginal_IsNotAFrame_AsInTheSplitter(self):
        original, songs = self.split([30])
        with open(original, "ab") as f:
            f.write(frame(99)[:200])
        self.assertTrue(self.run_verify(original, songs))
        self.assertTrue(any("not audio frames" in line for line in self.said))        # said once, as a note

    def test_PaddedFramesAndMixedBitrates_AreMeasuredRight(self):
        padded = bytearray(frame(1) + b"\0"); padded[2] |= 0x02                      # the padding bit adds one byte
        mixed = bytearray(417 - 0)                                                     # a 160 kbps frame is longer: 144 * 160000 // 44100 = 522
        longer = bytearray(522); longer[0:4] = bytes([0xFF, 0xFB, 0xA0, 0x00])
        data = frame(0) + bytes(padded) + bytes(longer) + frame(2)
        parsed = vs.frames(data)
        self.assertEqual([417, 418, 522, 417], list(parsed.length))
        self.assertEqual(0, parsed.junk)

    def test_ASongWithJunkATagOrACutOffFrame_Fails(self):
        for damage in (b"junkjunk", b"ID3\x03\x00\x00\x00\x00\x00\x04abcd", frame(5)[:100]):
            original, songs = self.split([30, 30])
            with open(songs[0], "ab") as f:
                f.write(damage)
            self.said.clear()
            self.assertFalse(self.run_verify(original, songs), damage[:6])
            self.assertIn("not whole audio frames", self.said[-1])

    def test_NamesTheBadFile_AndSkipsTheOriginalInTheFolder(self):
        original, songs = self.split([30, 30])
        bad = self.write("bad.mp3", b"hello" * 50)
        self.assertFalse(vs.verify(original, [songs[0], bad], say=self.said.append))
        self.assertIn("bad.mp3", self.said[-1])
        folder = os.path.join(self.dir, "in-one-folder")
        os.mkdir(folder)
        shutil.copy(original, os.path.join(folder, "Album.mp3"))
        shutil.copy(songs[0], os.path.join(folder, "Album 01.mp3"))
        shutil.copy(songs[1], os.path.join(folder, "Album 02.mp3"))
        self.assertEqual(0, vs.main([os.path.join(folder, "Album.mp3"), "-d", folder]))      # the original is not counted as a song
        self.assertEqual(1, vs.main([original]))                                             # no songs given
        self.assertEqual(1, vs.main([original, os.path.join(self.dir, "missing.mp3")]))      # a missing file

    def test_AnId3TagAndJunkAreNotAudio(self):
        original, songs = self.split([30, 30])
        with open(original, "rb") as f:
            data = f.read()
        id3 = b"ID3\x03\x00\x00\x00\x00\x00\x0a" + b"x" * 10
        self.write("o.mp3", id3 + data + b"TAG" + b"\0" * 125)
        self.assertTrue(self.run_verify(original, songs))

    def test_AChangedByteAMissingFrameAndAReorderedSong_Fail(self):
        original, songs = self.split([40, 25, 60])
        with open(songs[1], "rb") as f:
            data = bytearray(f.read())
        data[FRAME * 3 + 100] ^= 0xFF
        self.write("s2.mp3", bytes(data))
        self.assertFalse(self.run_verify(original, songs))
        self.assertIn("differs", self.said[-1])
        original, songs = self.split([40, 25, 60])
        self.assertFalse(self.run_verify(original, songs[:2]))                     # the last song is missing
        self.assertIn("in no song", self.said[-1])
        self.assertFalse(self.run_verify(original, [songs[1], songs[0], songs[2]])) # the order is wrong
        with open(songs[0], "rb") as f:
            self.write("short.mp3", f.read()[:-FRAME])
        self.assertFalse(self.run_verify(original, [os.path.join(self.dir, "short.mp3")] + songs[1:]))   # a frame cut from song 1

    def test_NotAnMp3_IsAnError_AndTheFolderModeSortsNumerically(self):
        garbage = self.write("g.mp3", b"hello world" * 100)
        with self.assertRaises(vs.VerifyError):
            vs.frames(open(garbage, "rb").read())
        self.assertEqual(1, vs.main([garbage, garbage]))
        original, songs = self.split([5] * 11)
        folder = os.path.join(self.dir, "out")
        os.mkdir(folder)
        for k, path in enumerate(songs, 1):
            os.replace(path, os.path.join(folder, f"Album {k:02d}.mp3"))
        self.assertEqual(0, vs.main([original, "-d", folder]))
        os.replace(os.path.join(folder, "Album 02.mp3"), os.path.join(folder, "Album 99.mp3"))   # a number out of order puts it last
        self.assertEqual(1, vs.main([original, "-d", folder]))


if __name__ == "__main__":
    unittest.main()
