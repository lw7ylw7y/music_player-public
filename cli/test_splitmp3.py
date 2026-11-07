"""Offline tests with synthetic data. Real-file test is opt-in: SPLITMP3_REAL_FILE=<path> (read-only)."""
import hashlib
import os
import tempfile
import unittest
from unittest import mock

import numpy as np

import splitmp3 as sm


def read(path):
    with open(path, "rb") as f:
        return f.read()

FRAME = 626  # MPEG-1 Layer III, 192 kbps, 44.1 kHz, no padding
HDR = bytes([0xFF, 0xFB, 0xB0, 0x00])


def frame(mdb=0, fill=0x11):
    f = bytearray([fill]) * FRAME
    f[0:4] = HDR
    f[4], f[5] = mdb >> 1, (mdb & 1) << 7
    return bytes(f)


def info_frame():
    f = bytearray(FRAME)
    f[0:4] = HDR
    f[36:40] = b"Info"
    return bytes(f)


def mp3(n, header=True, id3=True, tag=True):
    return ((b"ID3\x03\x00\x00\x00\x00\x00\x0a" + bytes(10)) if id3 else b"") + \
        (info_frame() if header else b"") + b"".join(frame(mdb=k % 3) for k in range(n)) + (b"TAG" + bytes(125) if tag else b"")


def song_db(songs=4, song_s=100, gap_s=4.0):
    parts = []
    for k in range(songs):
        parts.append(np.full(int(song_s / sm.HOP), -20.0))
        if k < songs - 1:
            parts.append(np.full(int(gap_s / sm.HOP), -100.0))
    return np.concatenate(parts)


class Parse(unittest.TestCase):
    def test_header_frame_id3_and_tag_are_not_audio(self):
        fi = sm.parse_frames(mp3(50))
        self.assertEqual((fi.count, fi.first, fi.junk), (51, 1, 0))
        self.assertEqual(fi.main_data_begin[1:4].tolist(), [0, 1, 2])

    def test_small_junk_is_skipped_and_counted(self):
        d = mp3(3000, tag=False)
        cut = 20 + FRAME * 1500  # a frame boundary after the 20-byte ID3 tag
        fi = sm.parse_frames(d[:cut] + b"\x00" * 100 + d[cut:])
        self.assertEqual(fi.junk, 100)
        self.assertEqual(fi.count, 3001)

    def test_not_an_mp3(self):
        with self.assertRaises(sm.NotMp3):
            sm.parse_frames(bytes(range(256)) * 10)
        with self.assertRaises(sm.NotMp3):
            sm.parse_frames(b"")

    def test_mostly_damaged_is_corrupt(self):
        d = mp3(40, tag=False)
        d += bytes([0x55]) * 5000  # >1% of the file is not MP3 data, after valid frames
        with self.assertRaises(sm.Corrupt):
            sm.parse_frames(d)


class Cuts(unittest.TestCase):
    def test_gaps_become_confident_cuts_in_the_middle(self):
        cuts = sm.find_cuts(song_db())
        self.assertEqual(len(cuts), 3)
        self.assertTrue(all(c.confident for c in cuts))
        self.assertAlmostEqual(cuts[0].time, 102.0, delta=0.1)

    def test_short_shallow_dip_is_not_confident(self):
        db = song_db(2, 200)
        a = int(100 / sm.HOP)
        db[a:a + 25] = -60
        by_time = {round(c.time): c for c in sm.find_cuts(db)}
        self.assertTrue(by_time[202].confident)
        self.assertFalse(by_time[100].confident)

    def test_cuts_within_a_minute_keep_the_stronger(self):
        db = song_db(2, 200)
        a = int(250 / sm.HOP)
        db[a:a + int(0.5 / sm.HOP)] = -100  # weak neighbour 48 s from the real gap
        cuts = sm.find_cuts(db)
        self.assertEqual([round(c.time) for c in cuts], [202])
        self.assertTrue(cuts[0].confident)

    def test_similar_rivals_make_the_cut_uncertain(self):
        db = song_db(2, 200, gap_s=4.0)
        a = int(250 / sm.HOP)
        db[a:a + int(3.5 / sm.HOP)] = -100
        cuts = sm.find_cuts(db)
        self.assertEqual(len(cuts), 1)
        self.assertFalse(cuts[0].confident)

    def test_silence_at_the_ends_is_not_a_cut(self):
        db = np.concatenate([np.full(int(5 / sm.HOP), -100.0), song_db(2, 150), np.full(int(5 / sm.HOP), -100.0)])
        self.assertEqual(len(sm.find_cuts(db)), 1)

    def test_single_song_has_no_cuts(self):
        self.assertEqual(sm.find_cuts(song_db(1, 300)), [])

    def test_long_stretch_is_flagged(self):
        self.assertEqual(sm.missed_stretches([200.0, 400.0], 1200.0), [(400.0, 1200.0)])
        self.assertEqual(sm.missed_stretches([200.0, 400.0], 600.0), [])

    def test_cut_snaps_to_self_contained_frame_inside_the_gap(self):
        fi = sm.parse_frames(mp3(6000))
        t = fi.start_time[3000]
        k = sm.snap_to_frame(fi, sm.Cut(t, 4.0, -100, True, ""))
        self.assertEqual(fi.main_data_begin[k], 0)
        self.assertLessEqual(abs(k - 3000), 2)


class Select(unittest.TestCase):
    def cuts(self):
        mk = lambda t, ok, st: sm.Cut(t, 1.0, -100, ok, "" if ok else "short gap", st)
        return [mk(100, True, 9), mk(200, False, 3), mk(300, True, 9), mk(400, False, 0.5), mk(500, False, 2)]

    def test_uncertain_cuts_are_added_strongest_first_until_the_count_is_reached(self):
        got = sm.select_cuts(self.cuts(), 5)   # 4 cuts: 2 confident + 2 of 3 uncertain
        self.assertEqual([c.time for c in got], [100, 200, 300, 500])

    def test_exact_count_of_confident_cuts_adds_nothing(self):
        self.assertEqual([c.time for c in sm.select_cuts(self.cuts(), 3)], [100, 300])

    def test_unreachable_counts_fall_back_to_confident_cuts(self):
        self.assertEqual([c.time for c in sm.select_cuts(self.cuts(), 2)], [100, 300])
        self.assertEqual([c.time for c in sm.select_cuts(self.cuts(), 9)], [100, 300])

    def test_find_cuts_sets_strength_so_that_a_silent_gap_beats_a_shallow_dip(self):
        db = song_db(3, 200)
        a = int(100 / sm.HOP)
        db[a:a + 25] = -60
        cuts = sm.find_cuts(db)
        deep = max(cuts, key=lambda c: c.length)
        shallow = min(cuts, key=lambda c: c.length)
        self.assertGreater(deep.strength, shallow.strength)


class Titles(unittest.TestCase):
    def test_every_divider_and_spaces_kept(self):
        want = ["一直很安静", "Hello World", "c"]
        for text in ("#一直很安静#Hello World#c", "一直很安静;Hello World;c", "一直很安静|Hello World|c",
                     "一直很安静/Hello World/c", "一直很安静\nHello World\r\nc\n", "一直很安静，Hello World、c",
                     "一直很安静；Hello World；c", "##一直很安静##Hello World#c#"):
            self.assertEqual(sm.parse_titles(text), want, text)

    def test_space_is_not_a_divider_and_comma_is_kept(self):
        self.assertEqual(sm.parse_titles("#Hello, Goodbye#A B C"), ["Hello, Goodbye", "A B C"])

    def test_illegal_file_name_characters_are_removed(self):
        self.assertEqual(sm.parse_titles('#What?#A:B*C#End...'), ["What", "ABC", "End"])
        with self.assertRaises(sm.BadTitles):
            sm.parse_titles("#???#ok")

    def test_names_use_number_then_title(self):
        fi = sm.parse_frames(mp3(300))
        songs = sm.plan_songs(fi, "Album", [101, 201], ["x", "y", "z"])
        self.assertEqual([s.name for s in songs], ["01 x.mp3", "02 y.mp3", "03 z.mp3"])

    def test_wrong_count_is_an_error(self):
        fi = sm.parse_frames(mp3(300))
        with self.assertRaises(sm.BadTitles):
            sm.plan_songs(fi, "Album", [101, 201], ["x", "y"])
        with self.assertRaises(sm.BadTitles):
            sm.plan_songs(fi, "Album", [101, 201], ["w", "x", "y", "z"])


class Write(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.src = os.path.join(self.tmp.name, "Album.mp3")
        self.data = mp3(300)
        with open(self.src, "wb") as f:
            f.write(self.data)
        self.fi = sm.parse_frames(self.data)
        self.out = os.path.join(self.tmp.name, "out")
        self.songs = sm.plan_songs(self.fi, "Album", [101, 201])
        self.quiet = lambda m: None

    def write(self, **kw):
        return sm.write_songs(self.data, self.fi, self.songs, self.out, self.src, say=self.quiet, **kw)

    def test_songs_are_the_original_bytes_in_order_and_original_untouched(self):
        before = hashlib.sha256(read(self.src)).hexdigest()
        self.assertEqual(self.write(), 3)
        names = sorted(os.listdir(self.out))
        self.assertEqual(names, ["Album 01.mp3", "Album 02.mp3", "Album 03.mp3"])
        joined = b"".join(read(os.path.join(self.out, n)) for n in names)
        audio = self.data[self.fi.offset[1]:self.fi.offset[-1] + FRAME]
        self.assertEqual(joined, audio)
        self.assertEqual(hashlib.sha256(read(self.src)).hexdigest(), before)

    def test_rerun_is_idempotent(self):
        self.write()
        self.assertEqual(self.write(), 0)

    def test_different_existing_file_stops_everything_before_writing(self):
        os.makedirs(self.out)
        with open(os.path.join(self.out, "Album 02.mp3"), "wb") as f:
            f.write(b"other")
        with self.assertRaises(sm.OutputProblem):
            self.write()
        self.assertEqual(os.listdir(self.out), ["Album 02.mp3"])
        self.assertEqual(self.write(overwrite=True), 3)

    def test_kill_mid_write_leaves_no_half_file_and_rerun_finishes(self):
        real = os.replace
        calls = []

        def die(a, b):
            calls.append(b)
            if len(calls) == 2:
                raise KeyboardInterrupt
            real(a, b)
        with mock.patch("os.replace", die), self.assertRaises(KeyboardInterrupt):
            self.write()
        self.assertEqual(os.listdir(self.out), ["Album 01.mp3"])
        self.assertEqual(self.write(), 2)

    def test_not_enough_space_writes_nothing(self):
        with mock.patch("shutil.disk_usage", return_value=mock.Mock(free=1000)), self.assertRaises(sm.OutputProblem):
            self.write()
        self.assertEqual(os.listdir(self.out), [])

    def test_output_may_not_replace_the_original(self):
        with self.assertRaises(sm.OutputProblem):
            sm.write_songs(self.data, self.fi, [sm.Song("Album.mp3", 1, 301, 0, False)],
                           self.tmp.name, self.src, say=self.quiet)

    def test_vbr_song_gets_its_own_xing_frame(self):
        # one 128 kbps frame (417 bytes) among 192 kbps ones makes the source VBR
        d = info_frame() + b"".join(frame() for _ in range(10)) + bytes([0xFF, 0xFB, 0x90, 0x00]) + bytes(413) \
            + b"".join(frame() for _ in range(29))
        fi = sm.parse_frames(d)
        songs = sm.plan_songs(fi, "V", [20])
        self.assertTrue(all(s.vbr for s in songs))
        out = os.path.join(self.tmp.name, "v")
        sm.write_songs(d, fi, songs, out, self.src, say=self.quiet)
        written = read(os.path.join(out, "V 01.mp3"))
        first = sm.parse_frames(written)
        self.assertEqual(first.first, 1)
        o = 4 + 32
        self.assertEqual(written[o:o + 4], b"Xing")
        self.assertEqual(int.from_bytes(written[o + 8:o + 12], "big"), songs[0].z - songs[0].a)
        self.assertEqual(int.from_bytes(written[o + 12:o + 16], "big"), len(written))


@unittest.skipUnless(os.environ.get("SPLITMP3_REAL_FILE"), "REAL FILE: set SPLITMP3_REAL_FILE to an MP3 (read-only)")
class RealFile(unittest.TestCase):
    def test_finds_cuts_in_a_real_album(self):
        p = os.environ["SPLITMP3_REAL_FILE"]
        db = sm.loudness(p)
        cuts = [c for c in sm.find_cuts(db) if c.confident]
        want = os.environ.get("SPLITMP3_EXPECTED_CUTS")
        if want:
            self.assertEqual(len(cuts), int(want))
        self.assertGreater(len(cuts), 0)


if __name__ == "__main__":
    unittest.main()
