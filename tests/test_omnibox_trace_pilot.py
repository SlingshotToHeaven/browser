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
            CREATE TABLE process (upid INTEGER, pid INTEGER, name TEXT, cmdline TEXT);
            CREATE TABLE thread (utid INTEGER, upid INTEGER, tid INTEGER, name TEXT);
            CREATE TABLE thread_track (id INTEGER, utid INTEGER);
            CREATE TABLE process_track (id INTEGER, upid INTEGER, type TEXT);
            CREATE TABLE slice (id INTEGER PRIMARY KEY, ts INTEGER, dur INTEGER, name TEXT, track_id INTEGER,
                parent_id INTEGER, arg_set_id INTEGER);
            CREATE TABLE flow (slice_out INTEGER, slice_in INTEGER);
            CREATE TABLE args (arg_set_id INTEGER, key TEXT, int_value INTEGER);
            CREATE TABLE ftrace_event (name TEXT);
            CREATE TABLE actual_frame_timeline_slice (
                id INTEGER PRIMARY KEY, upid INTEGER, ts INTEGER, dur INTEGER,
                surface_frame_token INTEGER, display_frame_token INTEGER, layer_name TEXT
            );
            CREATE TABLE stats (name TEXT, idx INTEGER, severity TEXT, value INTEGER);
            CREATE TABLE metadata (name TEXT, str_value TEXT);
            INSERT INTO process VALUES (1, 100, 'com.browser', 'com.browser'), (2, 200, 'surfaceflinger', NULL),
                (3, 300, 'old.browser', 'unrelated synthetic cmdline');
            INSERT INTO thread VALUES (1, 1, 100, 'com.browser'), (2, 1, 101, 'Browser-omnibox'),
                (3, 3, 300, 'old.browser'), (4, 3, 301, 'Browser-omnibox');
            INSERT INTO thread_track VALUES (1, 1), (2, 2), (3, 3), (4, 4);
        """)
        fixtures = Path(__file__).parent / "fixtures"
        self.query = (fixtures / "omnibox_trace_pilot.sql").read_text()
        self.health_query = (fixtures / "omnibox_trace_health.sql").read_text()
        self.attribution_query = (fixtures / "omnibox_trace_attribution.sql").read_text()

    def diagnostics(self, query=None):
        return {(row["section"], row["name"], row["idx"]): row["value"]
                for row in self.db.execute(query or self.health_query)}

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
            INSERT INTO stats (severity, value) VALUES ('data_loss', 0), ('info', 99);
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
            INSERT INTO stats (severity, value) VALUES ('data_loss', 2);
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

    def test_diagnostics_show_raw_signals_when_attributed_counts_are_zero(self):
        self.db.executescript("""
            UPDATE process SET name = NULL;
            UPDATE thread SET name = NULL;
            INSERT INTO ftrace_event VALUES ('print'), ('print'), ('sched_switch');
            INSERT INTO slice (ts, dur, name, track_id) VALUES
                (100, 20, 'deliverInputEvent src=0x101', 1),
                (105, 5, 'Browser.omnibox.edit', 1),
                (200, 6, 'Browser.omnibox.lookup', 2),
                (220, 5, 'Browser.omnibox.localRows', 1),
                (300, -1, 'Browser.omnibox.lookup', 2);
            INSERT INTO actual_frame_timeline_slice
                (upid, ts, dur, surface_frame_token, display_frame_token, layer_name) VALUES
                (1, 224, 12, 10, 11, 'TX - com.browser/synthetic layer name'),
                (2, 230, 10, NULL, 11, NULL);
        """)
        strict = self.summary()
        for key in ("browser_processes", "dispatched_edits", "worker_lookups", "post_edit_surface_display_pairs"):
            self.assertEqual(0, strict[key])
        found = self.diagnostics()
        for label in ("named_processes", "browser_named_processes", "named_threads", "omnibox_named_threads"):
            self.assertEqual(0, found[("process", label, None)])
        self.assertEqual(2, found[("process", "processes_with_cmdline", None)])
        self.assertEqual(1, found[("process", "browser_cmdline_matches", None)])
        self.assertEqual(3, found[("ftrace", "raw_event_rows", None)])
        self.assertEqual(2, found[("ftrace", "raw_print_event_rows", None)])
        for label in ("dispatch", "omnibox_edit", "omnibox_lookup", "omnibox_local_rows"):
            self.assertEqual(1, found[("slice_complete", label, None)])
        self.assertEqual(2, found[("slice_total", "omnibox_lookup", None)])
        self.assertEqual(0, found[("slice_total", "omnibox_clipboard", None)])
        self.assertEqual(2, found[("frame", "actual_rows", None)])
        self.assertEqual(1, found[("frame", "completed_surface_rows", None)])
        self.assertEqual(1, found[("frame", "completed_display_rows", None)])
        self.assertEqual(1, found[("frame", "browser_layer_label_surface_rows", None)])
        for raw in ("src=0x101", "unrelated synthetic cmdline", "synthetic layer name"):
            self.assertNotIn(raw, repr(found))

    def test_diagnostics_preserve_missing_stats_and_detect_loss_outside_data_loss_severity(self):
        self.assertEqual(0, self.diagnostics()[("config", "embedded_config_rows", None)])
        self.db.executescript("""
            INSERT INTO metadata VALUES ('trace_config_pbtxt', 'do not return arbitrary config text'),
                ('trace_config_pbtxt', NULL), ('trace_config_pbtxt', ''), ('other_metadata', 'unrelated'),
                ('unique_session_name', 'unrelated session');
            INSERT INTO stats VALUES
                ('traced_buf_buffer_size', 0, 'info', 16777216),
                ('traced_buf_buffer_size', 1, 'info', 4194304),
                ('traced_buf_chunks_overwritten', 0, 'info', 5),
                ('traced_buf_chunks_discarded', 1, 'info', 0),
                ('ftrace_cpu_overrun_delta', 3, 'info', 2),
                ('ftrace_setup_errors', NULL, 'notice', 1),
                ('ftrace_bundle_tokenizer_errors', NULL, 'error', 3),
                ('unselected_zero', NULL, 'data_loss', 0);
        """)
        self.assertEqual(0, self.summary()["nonzero_data_loss_counters"])
        found = self.diagnostics()
        self.assertEqual(1, found[("config", "embedded_config_rows", None)])
        self.assertEqual(0, found[("config", "retry_session_matches", None)])
        self.assertEqual(5, found[("stat", "traced_buf_chunks_overwritten", 0)])
        self.assertEqual(0, found[("stat", "traced_buf_chunks_discarded", 1)])
        self.assertEqual(2, found[("stat", "ftrace_cpu_overrun_delta", 3)])
        self.assertEqual(1, found[("stat", "ftrace_setup_errors", None)])
        self.assertEqual(3, found[("stat", "ftrace_bundle_tokenizer_errors", None)])
        self.assertNotIn(("stat", "traced_buf_chunks_discarded", 0), found)
        self.assertNotIn(("stat", "unselected_zero", None), found)
        self.assertNotIn("arbitrary", repr(found))
        self.db.execute("UPDATE metadata SET str_value = ? WHERE name = 'unique_session_name'",
                        ("browser-omnibox-readiness-retry",))
        self.assertEqual(1, self.diagnostics()[("config", "retry_session_matches", None)])

    def test_separated_dispatch_is_diagnostic_only_even_with_frames_and_async_window(self):
        self.db.executescript("""
            INSERT INTO process_track VALUES (10, 1, 'atrace_async_slice'), (11, 3, 'atrace_async_slice'),
                (12, 1, 'other_track_type');
            INSERT INTO args VALUES (21, 'cookie', -2147483647), (22, 'cookie', 16), (23, 'another_arg', 10);
            INSERT INTO slice (ts, dur, name, track_id, arg_set_id) VALUES
                (10000000, 820539, 'deliverInputEvent src=0x101 eventTimeNano=123 id=0x80000001', 1, NULL),
                (13838577, 877692, 'Browser.omnibox.edit', 1, NULL),
                (15000000, 300000, 'deliverInputEvent src=0x101 eventTimeNano=124 id=0xa', 1, NULL),
                (120000000, 500000, 'Browser.omnibox.lookup', 2, NULL),
                (130000000, 500000, 'Browser.omnibox.localRows', 1, NULL),
                (9900000, 5000000, 'deliverInputEvent', 10, 21),
                (9900000, 6000000, 'deliverInputEvent', 10, 22),
                (1000000, 1000, 'deliverInputEvent', 10, 21),
                (9900000, 5000000, 'deliverInputEvent', 11, 21),
                (9900000, 5000000, 'deliverInputEvent', 12, 21),
                (14900000, 8000000, 'deliverInputEvent', 10, 23);
            INSERT INTO actual_frame_timeline_slice (upid, ts, dur, surface_frame_token, display_frame_token) VALUES
                (1, 5000000, 1000, 1, 11), (2, 5000000, 2000, NULL, 11),
                (1, 15000000, 3000000, 2, 12), (2, 16000000, 4000000, NULL, 12),
                (2, 16000000, 4000000, NULL, 12),
                (1, 15000000, 6000000, 3, 13), (2, 16000000, 2000000, NULL, 13),
                (3, 16000000, 8000000, NULL, 13),
                (1, 131000000, 2000000, 4, 14), (2, 131000000, 3000000, 0, 14),
                (3, 131000000, 2000000, 5, 15), (2, 132000000, 5000000, NULL, 15);
        """)
        strict = self.summary()
        self.assertEqual(1, strict["worker_lookups"])
        self.assertEqual(0, strict["dispatched_edits"])
        self.assertEqual(0, strict["post_edit_surface_display_pairs"])
        found = self.diagnostics(self.attribution_query)
        self.assertEqual(1, found[("shape", "single_edit_anchor", None)])
        self.assertEqual(4, found[("shape", "async_windows", None)])
        self.assertEqual(3, found[("shape", "async_windows_with_cookie", None)])
        self.assertEqual(-3_018_038, found[("dispatch", "end_offset_ns", 1)])
        self.assertEqual(1_161_423, found[("dispatch", "start_offset_ns", 2)])
        self.assertEqual(1, found[("dispatch", "event_id_matched_windows", 1)])
        self.assertEqual(0, found[("dispatch", "event_id_matched_windows", 2)])
        self.assertEqual(1, found[("dispatch", "matched_windows_covering_edit", 1)])
        self.assertEqual(0, found[("flow", "input_to_edit_or_ancestor_edges", None)])
        self.assertEqual(3, found[("frame", "surfaces_after_main_edit", None)])
        self.assertEqual(2, found[("frame", "pairs_after_main_edit", None)])
        self.assertEqual(1, found[("frame", "pairs_after_later_local_rows", None)])
        self.assertNotIn("0x80000001", repr(found))

    def test_attribution_reports_only_directed_flows_from_selected_input(self):
        self.db.executescript("""
            INSERT INTO process_track VALUES (10, 1, 'atrace_async_slice');
            INSERT INTO args VALUES (20, 'cookie', 16), (21, 'cookie', 17);
            INSERT INTO slice (id, ts, dur, name, track_id, parent_id, arg_set_id) VALUES
                (1, 100, 20, 'deliverInputEvent src=0x101 eventTimeNano=0 id=0x10', 1, NULL, NULL),
                (2, 140, 20, 'callback', 1, NULL, NULL),
                (3, 141, 8, 'Browser.omnibox.edit', 1, 2, NULL),
                (4, 100, 10, 'deliverInputEvent', 3, NULL, NULL),
                (5, 99, 52, 'deliverInputEvent', 10, NULL, 20),
                (6, 99, 52, 'deliverInputEvent', 10, NULL, 21);
            INSERT INTO flow VALUES (1, 2), (5, 3), (4, 3), (3, 1), (6, 2);
        """)
        found = self.diagnostics(self.attribution_query)
        self.assertEqual(5, found[("flow", "total_edges_in_trace", None)])
        self.assertEqual(1, found[("flow", "input_to_exact_edit_edges", None)])
        self.assertEqual(2, found[("flow", "input_to_edit_or_ancestor_edges", None)])
        self.assertEqual(1, found[("flow_roles", "async_window_to_edit", None)])
        self.assertEqual(1, found[("flow_roles", "dispatch_to_ancestor", None)])
        self.assertEqual(0, self.summary()["dispatched_edits"])

    def test_attribution_does_not_select_an_edit_when_multiple_are_present(self):
        self.db.executescript("""
            INSERT INTO slice (ts, dur, name, track_id) VALUES (100, 20, 'deliverInputEvent', 1),
                (105, 5, 'Browser.omnibox.edit', 1), (200, 5, 'Browser.omnibox.edit', 1);
        """)
        found = self.diagnostics(self.attribution_query)
        self.assertEqual(2, found[("shape", "main_edit_rows", None)])
        self.assertEqual(0, found[("shape", "single_edit_anchor", None)])
        self.assertFalse(any(section == "dispatch" for section, _, _ in found))


class TraceSetupSqlTest(unittest.TestCase):
    def test_counts_setup_categories_without_returning_message_text(self):
        with closing(sqlite3.connect(":memory:")) as db:
            db.executescript("""
                CREATE TABLE __intrinsic_stats (name TEXT, key INTEGER);
                CREATE TABLE __intrinsic_trace_import_logs (stat_key INTEGER, arg_set_id INTEGER);
                CREATE TABLE args (arg_set_id INTEGER, key TEXT, string_value TEXT);
                INSERT INTO __intrinsic_stats VALUES ('ftrace_setup_errors', 7), ('another_stat', 16);
                INSERT INTO __intrinsic_trace_import_logs VALUES
                    (7, 1), (7, 2), (7, 3), (7, 4), (7, 5), (7, 6), (7, 7), (7, NULL), (16, 8);
                INSERT INTO args VALUES
                    (1, 'message', 'Ftrace event failed: ftrace/print'),
                    (2, 'message', 'Ftrace event unknown: ftrace/print'),
                    (3, 'message', 'Ftrace event failed: synthetic unrelated name'),
                    (4, 'message', 'Ftrace event unknown: synthetic unrelated name'),
                    (5, 'message', 'Atrace failures: synthetic app text'),
                    (6, 'message', 'Ftrace exclusive feature error: synthetic detail'),
                    (7, 'message', 'synthetic unrecognized notice'),
                    (7, 'some_key', 'Ftrace event failed: ftrace/print'),
                    (8, 'message', 'Atrace failures: irrelevant stat');
            """)
            query = (Path(__file__).parent / "fixtures/omnibox_trace_setup.sql").read_text()
            self.assertEqual([
                ("atrace_failure", 1), ("ftrace_event_failed", 1), ("ftrace_event_unknown", 1),
                ("ftrace_exclusive_feature", 1), ("ftrace_print_setup", 2), ("other_or_missing_detail", 2),
            ], db.execute(query).fetchall())


if __name__ == "__main__":
    unittest.main()
