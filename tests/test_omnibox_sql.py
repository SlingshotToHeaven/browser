"""Exercise the Android address-bar SQL on host SQLite, not Android's connection pool."""
from contextlib import closing
import sqlite3
import unittest

from fixtures import omnibox


class OmniboxSqlTest(unittest.TestCase):
    def test_recent_matches_and_rank_are_preserved_with_a_cutoff(self):
        for store in omnibox.QUERY:
            with self.subTest(store=store), closing(sqlite3.connect(":memory:")) as db:
                omnibox.seed(db, store, 4000)
                query = omnibox.QUERY[store]
                rows = db.execute(query, omnibox.args_for("PERF18RECENT")).fetchall()
                self.assertEqual([f"Fixture perf18recent {store} {i:06}" for i in (4000, 3999, 3998)],
                                 [title for title, _ in rows])
                # Match inside a URL as well as in the middle of a title.
                self.assertEqual([rows[0]], db.execute(query, omnibox.args_for("perf18.invalid/final")).fetchall())
                self.assertEqual([], db.execute(query, omnibox.args_for("perf18archive")).fetchall())
                self.assertEqual(1, len(db.execute(omnibox.OLD_QUERY[store], omnibox.args_for("perf18archive")).fetchall()))

    def test_pattern_characters_are_literal_and_deleted_rows_are_not_cached(self):
        for store in omnibox.QUERY:
            with self.subTest(store=store), closing(sqlite3.connect(":memory:")) as db:
                omnibox.seed(db, store, 10)
                query = omnibox.QUERY[store]
                args = omnibox.args_for(r"route_50%\quoted's")
                self.assertEqual(1, len(db.execute(query, args).fetchall()))
                for near_match in (r"routeX50%\quoted's", r"route_50X\quoted's", "route_50%quoted's"):
                    self.assertEqual([], db.execute(query, omnibox.args_for(near_match)).fetchall())
                db.execute(f"DELETE FROM {store} WHERE id=4")
                self.assertEqual([], db.execute(query, args).fetchall())

    def test_only_links_can_suggest_and_folder_heavy_history_is_bounded(self):
        with closing(sqlite3.connect(":memory:")) as db:
            db.executescript(omnibox.SCHEMA["bookmarks"])
            insert = "INSERT INTO bookmarks (title,url,is_folder,created_at) VALUES (?,?,?,?)"
            db.execute(insert, ("Needle in old bookmark", "https://old.invalid", 0, 0))
            db.executemany(insert, (("Needle folder", "https://must-not-show.invalid", 1, i)
                                    for i in range(1, omnibox.WINDOW + 800)))
            query = omnibox.QUERY["bookmarks"]
            self.assertEqual([], db.execute(query, omnibox.args_for("needle")).fetchall())
            for url in (None, "", "https://oversized.invalid/" + 8192 * "x"):
                db.execute(insert, ("Needle with invalid target", url, 0, 50_000))
            db.execute(insert, ("Needle with link", "https://valid.invalid", 0, 50_001))
            self.assertEqual([("Needle with link", "https://valid.invalid")],
                             db.execute(query, omnibox.args_for("needle")).fetchall())

    def test_equal_visit_dates_do_not_extend_the_window(self):
        with closing(sqlite3.connect(":memory:")) as db:
            db.executescript(omnibox.SCHEMA["history"])
            db.executemany("INSERT INTO history (title,url,visited_at) VALUES (?,?,0)",
                           (("Match" if i >= omnibox.WINDOW else "Other", "https://host.invalid")
                            for i in range(omnibox.WINDOW * 4)))
            query = omnibox.QUERY["history"]
            self.assertEqual([], db.execute(query, omnibox.args_for("match")).fetchall())

    def test_plan_uses_ordered_candidate_scan_and_large_missing_work_stays_flat(self):
        for store, large_size in (("history", 50_000), ("bookmarks", 20_000)):
            counts = {}
            for size in (4000, large_size):
                with self.subTest(store=store, size=size), closing(sqlite3.connect(":memory:")) as db:
                    omnibox.seed(db, store, size)
                    sql = omnibox.QUERY[store]
                    for label, term in omnibox.TERMS.items():
                        args = omnibox.args_for(term)
                        plan = omnibox.plan(db, sql, args)
                        self.assertIn("CO-ROUTINE", " ".join(row[3] for row in plan))
                        scan = next(row for row in plan if f"SCAN {store}" in row[3])
                        if store == "history":
                            self.assertIn("idx_history_visited_at", scan[3])
                        self.assertFalse(any(row[1] == scan[1] and "TEMP B-TREE" in row[3] for row in plan), plan)
                        steps, found = omnibox.measured_steps(db, sql, args)
                        self.assertEqual(0 if label == "miss" else 3, len(found))
                        if size == 4000:
                            counts[label] = steps
                        else:
                            self.assertLessEqual(steps, counts[label] + 500, plan)
                    if size == large_size:
                        args = omnibox.args_for(omnibox.TERMS["miss"])
                        old_count, _ = omnibox.measured_steps(db, omnibox.OLD_QUERY[store], args)
                        self.assertGreater(old_count, counts["miss"] * 4)


if __name__ == "__main__":
    unittest.main()
