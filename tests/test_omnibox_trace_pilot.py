"""Host checks of the pilot inputs; no Android capture or timing is implied."""
from contextlib import closing
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from pathlib import Path
import sqlite3
from threading import Thread
import unittest

from fixtures import omnibox


class TracePilotPageTest(unittest.TestCase):
    def test_only_fixed_hit_page_is_served_and_no_other_path_is_read(self):
        with ThreadingHTTPServer((omnibox.TRACE_HOST, 0), omnibox.TracePageHandler) as server:
            runner = Thread(target=server.serve_forever, kwargs={"poll_interval": .05}, daemon=True)
            runner.start()
            try:
                for path, expected in ((omnibox.TRACE_PATH, 200), ("/README.md", 404),
                                       (omnibox.TRACE_PATH + "?unrecognized", 404)):
                    with closing(HTTPConnection(omnibox.TRACE_HOST, server.server_port, timeout=4)) as conn:
                        conn.request("GET", path)
                        response = conn.getresponse()
                        self.assertEqual(expected, response.status)
                        self.assertEqual("no-store", response.headers["Cache-Control"])
                        body = response.read()
                        if expected == 200:
                            self.assertIn(f"<title>Fixture {omnibox.TRACE_TERM}</title>".encode(), body)
                            self.assertNotIn(b"<script", body)
                            self.assertNotIn(b"src=", body)
                        else:
                            self.assertEqual(b"", body)
            finally:
                server.shutdown()
                runner.join(5)


class TracePilotSqlTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            CREATE TABLE process (upid INTEGER, pid INTEGER, name TEXT);
            CREATE TABLE thread (utid INTEGER, upid INTEGER, tid INTEGER, name TEXT);
            CREATE TABLE thread_track (id INTEGER, utid INTEGER);
            CREATE TABLE slice (id INTEGER PRIMARY KEY, ts INTEGER, dur INTEGER, name TEXT, track_id INTEGER);
            CREATE TABLE actual_frame_timeline_slice (
                id INTEGER PRIMARY KEY, upid INTEGER, ts INTEGER, dur INTEGER,
                surface_frame_token INTEGER, display_frame_token INTEGER
            );
            CREATE TABLE stats (severity TEXT, value INTEGER);
            INSERT INTO process VALUES (1, 100, 'com.browser'), (2, 200, 'surfaceflinger'), (3, 300, 'old.browser');
            INSERT INTO thread VALUES (1, 1, 100, 'com.browser'), (2, 1, 101, 'Browser-omnibox'),
                (3, 3, 300, 'old.browser'), (4, 3, 301, 'Browser-omnibox');
            INSERT INTO thread_track VALUES (1, 1), (2, 2), (3, 3), (4, 4);
        """)
        self.query = (Path(__file__).parent / "fixtures/omnibox_trace_pilot.sql").read_text()

    def summary(self):
        row, = self.db.execute(self.query).fetchall()
        return dict(row)

    def test_counts_observed_work_by_app_thread_and_pairs_frames_only_once(self):
        self.db.executescript("""
            INSERT INTO slice (ts, dur, name, track_id) VALUES
                (100, 20, 'deliverInputEvent src=0x101', 1),
                (105, 5, 'Browser.omnibox.edit', 1),
                (210, 6, 'Browser.omnibox.lookup', 2),
                (223, 5, 'Browser.omnibox.localRows', 1),
                (224, 1, 'Browser.omnibox.clipboard', 1),
                (105, 5, 'Browser.omnibox.edit', 3),
                (210, 6, 'Browser.omnibox.lookup', 4),
                (220, -1, 'Browser.omnibox.lookup', 2);
            INSERT INTO actual_frame_timeline_slice (upid, ts, dur, surface_frame_token, display_frame_token) VALUES
                (1, 224, 12, 10, 11), (2, 230, 7, NULL, 11), (2, 231, 7, 0, 11),
                (3, 224, 12, 12, 11);
            INSERT INTO stats VALUES ('data_loss', 0), ('info', 99);
        """)
        self.assertEqual({
            "browser_processes": 1, "main_dispatches": 1, "main_edits": 1,
            "dispatched_edits": 1, "worker_lookups": 1, "other_lookups": 0,
            "main_local_rows_slices": 1, "clipboard_reads": 1,
            "app_actual_surface_slices": 1, "post_edit_surface_display_pairs": 1,
            "nonzero_data_loss_counters": 0,
        }, self.summary())

    def test_unrelated_dispatch_old_frame_and_unmatched_display_dont_qualify(self):
        self.db.executescript("""
            INSERT INTO slice (ts, dur, name, track_id) VALUES
                (100, 20, 'deliverInputEvent', 3), (105, 5, 'Browser.omnibox.edit', 1),
                (210, 6, 'Browser.omnibox.lookup', 1);
            INSERT INTO actual_frame_timeline_slice (upid, ts, dur, surface_frame_token, display_frame_token) VALUES
                (1, 10, 8, 3, 4), (2, 13, 7, 0, 4),
                (1, 200, 12, 10, 11), (2, 204, -1, 0, 11), (2, 190, 13, 0, 11),
                (1, 210, 5, 0, 11), (3, 210, 5, 0, 11);
            INSERT INTO stats VALUES ('data_loss', 2);
        """)
        report = self.summary()
        self.assertEqual(1, report["main_edits"])
        self.assertEqual(0, report["dispatched_edits"])
        self.assertEqual(1, report["other_lookups"])
        self.assertEqual(0, report["post_edit_surface_display_pairs"])
        self.assertEqual(1, report["nonzero_data_loss_counters"])
        self.db.execute("INSERT INTO slice (ts, dur, name, track_id) VALUES (100, 20, 'deliverInputEvent', 1)")
        report = self.summary()
        self.assertEqual(1, report["dispatched_edits"])
        self.assertEqual(2, report["app_actual_surface_slices"])
        self.assertEqual(0, report["post_edit_surface_display_pairs"])


if __name__ == "__main__":
    unittest.main()
