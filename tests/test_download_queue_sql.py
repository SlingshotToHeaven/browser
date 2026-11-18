"""Run the downloader's DDL and WHERE clauses on host SQLite (not Android's SQLiteOpenHelper)."""
import ast
from contextlib import closing
from pathlib import Path
import re
import sqlite3
import tempfile
import unittest


JAVA = (Path(__file__).resolve().parents[1] /
        "downloader/src/main/java/com/privacy/downloader/db/DownloadDbHelper.java").read_text(encoding="utf-8")


HISTORY_JAVA = (Path(__file__).resolve().parents[1] /
        "downloader/src/main/java/com/privacy/downloader/db/DownloadHistory.java").read_text(encoding="utf-8")


def java_string(expression, source=JAVA):
    # The tested expressions are concatenations of literals and named string constants.
    parts = re.findall(r'"(?:[^"\\]|\\.)*"|[\w.]+|\+', expression)
    assert parts and all(part == "+" for part in parts[1::2]), expression
    return "".join(ast.literal_eval(part) if part.startswith('"') else java_constant(part, source) for part in parts[::2])


def java_constant(name, source=JAVA):
    if name.startswith("DownloadHistory."):
        name = name.removeprefix("DownloadHistory.")
        source = HISTORY_JAVA
    expression = re.search(r"\bString " + re.escape(name) + r"\s*=\s*(.*?);", source, re.DOTALL).group(1)
    return java_string(expression, source)


INDEX = java_string(re.search(r'db\.execSQL\(("CREATE UNIQUE INDEX .*?)\);', JAVA).group(1))
QUERY_INDEXES = [java_string(expression) for expression in re.findall(r"db\.execSQL\(([^()]+)\);",
    JAVA.split("private static void createQueryIndexes", 1)[1].split("\n    }", 1)[0])]
STAGING_WHERE = java_constant("STAGING_WORKER")
ACTIVE_WHERE = java_constant("ACTIVE_WORKER")
CHECKPOINT_WHERE = java_constant("CHECKPOINT_WORKER")
PUBLICATION_WHERE = java_constant("PUBLISHING_WORKER")
SHAREABLE_WHERE = java_constant("SHAREABLE_ROW")
ROW_WHERE = java_constant("QUEUE_ROW")

# The baseline downloads table (schema v3). v4–v7 fixtures are derived with the real upgrade DDL.
V3_DOWNLOADS = """
CREATE TABLE downloads (
    id TEXT PRIMARY KEY, url TEXT NOT NULL, original_url TEXT, file_name TEXT, dest_path TEXT, temp_path TEXT,
    mime_type TEXT, total_bytes INTEGER DEFAULT -1, downloaded_bytes INTEGER DEFAULT 0, status TEXT NOT NULL,
    num_segments INTEGER DEFAULT 4, supports_range INTEGER DEFAULT 0, etag TEXT, last_modified TEXT, cookies TEXT,
    user_agent TEXT, referrer TEXT, created_time INTEGER, finished_time INTEGER, error_message TEXT,
    wifi_only INTEGER DEFAULT 0, audio_url TEXT, audio_bytes INTEGER DEFAULT -1)
"""


def create(db, initial_version=8):
    db.execute(java_constant("createSegmentsTable"))
    db.execute(java_constant("createDownloadsTable") if initial_version == 8 else V3_DOWNLOADS)
    if initial_version == 8:
        db.execute(INDEX)
        for sql in QUERY_INDEXES:
            db.execute(sql)
    else:
        migrate(db, 3, initial_version)
    db.commit()


def migrate(db, before, after):
    for version in range(before + 1, after + 1):
        body = re.search(r"if \(oldVersion < " + str(version) + r"\) \{(.*?)^        \}", JAVA, re.MULTILINE | re.DOTALL).group(1)
        for expression in re.findall(r"db\.execSQL\((.*?)\);", body, re.DOTALL):
            db.execute(java_string(expression))
        if "createDestinationIndex(db)" in body:
            db.execute(INDEX)
        if "backfillNames(db)" in body:
            # Host DDL fixtures here use ASCII names; Android tests cover the real Java Unicode backfill.
            db.execute("UPDATE downloads SET name_search=lower(coalesce(file_name,'')), name_order=lower(coalesce(file_name,''))")
        if "createQueryIndexes(db)" in body:
            for sql in QUERY_INDEXES:
                db.execute(sql)


def table_shape(db, table):
    # ALTER COLUMN appends columns; compare names/constraints regardless of column order.
    return sorted(row[1:] for row in db.execute("PRAGMA table_info(" + table + ")"))


class DownloadQueueSqlTest(unittest.TestCase):
    def test_upgrades_keep_nonempty_checkpoints_and_legacy_duplicate_names(self):
        with closing(sqlite3.connect(":memory:")) as fresh:
            create(fresh)
            for version in (3, 4, 5, 6, 7):
                with self.subTest(version=version), closing(sqlite3.connect(":memory:")) as db:
                    create(db, version)
                    db.execute("INSERT INTO downloads (id,url,status,dest_path,temp_path,downloaded_bytes) "
                               "VALUES ('old','fixture','PAUSED','/files/legacy','/files/old.part',6)")
                    db.execute("INSERT INTO downloads (id,url,status,dest_path) VALUES ('done','fixture','COMPLETED','/files/legacy')")
                    db.execute("INSERT INTO download_segments VALUES ('old',0,0,9,6,'PAUSED')")
                    if version < 7:
                        db.execute("UPDATE downloads SET cookies='legacy_cookie', user_agent='old-ua', referrer='https://page.test/path?q=secret'")
                    db.execute("UPDATE downloads SET file_name='Legacy.ZIP' WHERE id='old'")
                    if version >= 4:
                        db.execute("UPDATE downloads SET etag='strong',verified_range_url='fixture' WHERE id='old'")
                    if version >= 5:
                        db.execute("UPDATE downloads SET destination_key='/files/legacy',expected_sha256=? WHERE id='old'", ('a' * 64,))
                    with db:
                        migrate(db, version, 8)
                    self.assertEqual(table_shape(fresh, "downloads"), table_shape(db, "downloads"))
                    self.assertEqual(sorted(fresh.execute("PRAGMA index_list(downloads)").fetchall()),
                                     sorted(db.execute("PRAGMA index_list(downloads)").fetchall()))
                    self.assertEqual(("legacy.zip", "legacy.zip"), db.execute("SELECT name_search,name_order FROM downloads WHERE id='old'").fetchone())
                    self.assertEqual(table_shape(fresh, "download_segments"), table_shape(db, "download_segments"))
                    self.assertEqual([("done", "COMPLETED", "/files/legacy", None), ("old", "PAUSED", "/files/legacy", None)],
                                     db.execute("SELECT id,status,dest_path,attempt_token FROM downloads ORDER BY id").fetchall())
                    self.assertEqual((6, "/files/old.part", "fixture" if version >= 4 else None,
                                      "/files/legacy" if version >= 5 else None, 'a' * 64 if version >= 5 else None),
                                     db.execute("SELECT downloaded_bytes,temp_path,verified_range_url,destination_key,expected_sha256 "
                                                "FROM downloads WHERE id='old'").fetchone())
                    self.assertEqual((6,), db.execute("SELECT current_byte FROM download_segments").fetchone())
                    self.assertEqual([(None, None, None)] * 2, db.execute("SELECT cookies,user_agent,referrer FROM downloads").fetchall())
                    self.assertIsNone(db.execute("SELECT id FROM downloads WHERE " + SHAREABLE_WHERE, ('done',)).fetchone())

    def test_reopen_only_invalidates_interrupted_workers(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "test-downloads.db"
            with closing(sqlite3.connect(path)) as db:
                create(db)
                states = ("RUNNING", "CONNECTING", "PENDING", "PAUSED", "WAITING_FOR_WIFI", "WAITING_FOR_BATTERY",
                          "WAITING_FOR_SCHEDULE", "COMPLETED", "FAILED", "CANCELLED")
                with db:
                    for state in states:
                        db.execute("INSERT INTO downloads (id,url,status,attempt_token,temp_path,dest_path,destination_key) "
                                   "VALUES (?,'fixture',?,'old','/files/checkpoint.part',?,?)", (state, state, state, state))
                    db.execute("INSERT INTO download_segments VALUES ('RUNNING',0,0,9,4,'RUNNING')")
            with closing(sqlite3.connect(path)) as db, db:
                recover = "UPDATE downloads SET status='PENDING',attempt_token=NULL,error_message=NULL WHERE " + java_constant("TRANSFERRING")
                self.assertEqual(2, db.execute(recover).rowcount)
                self.assertEqual(0, db.execute(recover).rowcount)  # Idempotent startup before admission.
                for state in states:
                    interrupted = state in {"RUNNING", "CONNECTING"}
                    self.assertEqual(("PENDING" if interrupted else state, None if interrupted else "old", "/files/checkpoint.part", state),
                                     db.execute("SELECT status,attempt_token,temp_path,destination_key FROM downloads WHERE id=?", (state,)).fetchone())
                self.assertEqual((4,), db.execute("SELECT current_byte FROM download_segments").fetchone())

    def test_stopped_and_replaced_attempts_cannot_publish_mutate_status_or_rebind(self):
        with closing(sqlite3.connect(":memory:")) as db:
            create(db)
            db.execute("INSERT INTO downloads (id,url,status,dest_path,destination_key,temp_path,attempt_token) "
                       "VALUES ('job','fixture','RUNNING','/files/out','/files/out','/files/stage.part','old')")
            worker_status = "UPDATE downloads SET status='FAILED' WHERE " + ACTIVE_WHERE
            worker_progress = "UPDATE downloads SET downloaded_bytes=99 WHERE " + ACTIVE_WHERE
            bind_staging = "UPDATE downloads SET temp_path='/files/wrong.part' WHERE " + STAGING_WHERE + "=?"
            publish = "UPDATE downloads SET status='COMPLETED',temp_path=NULL WHERE " + PUBLICATION_WHERE
            checkpoint = "UPDATE downloads SET downloaded_bytes=4 WHERE " + CHECKPOINT_WHERE
            transition = "UPDATE downloads SET status=? WHERE " + ROW_WHERE + "=?"

            self.assertEqual(1, db.execute(transition, ("PAUSED", "job", "RUNNING", "old")).rowcount)
            for status in ("PAUSED", "WAITING_FOR_WIFI", "WAITING_FOR_SCHEDULE", "WAITING_FOR_BATTERY", "PENDING", "CANCELLED"):
                db.execute("UPDATE downloads SET status=? WHERE id='job'", (status,))
                self.assertEqual(0, db.execute(worker_status, ("job", "old")).rowcount)
                self.assertEqual(0, db.execute(worker_progress, ("job", "old")).rowcount)
                self.assertEqual(0, db.execute(bind_staging, ("job", "old", "/files/out", "/files/stage.part")).rowcount)
                self.assertEqual(0, db.execute(publish, ("job", "old", "/files/out", "/files/stage.part")).rowcount)
                # A pause can keep the synced final checkpoint even if resume queued the job before drain.
                self.assertEqual(0 if status == "CANCELLED" else 1,
                                 db.execute(checkpoint, ("job", "old", "/files/stage.part")).rowcount)

            db.execute("UPDATE downloads SET status='RUNNING',attempt_token='new' WHERE id='job'")
            self.assertEqual(0, db.execute(transition, ("CANCELLED", "job", "RUNNING", "old")).rowcount)
            self.assertEqual(0, db.execute(worker_status, ("job", "old")).rowcount)
            self.assertEqual(0, db.execute(worker_progress, ("job", "old")).rowcount)
            self.assertEqual(0, db.execute(checkpoint, ("job", "old", "/files/stage.part")).rowcount)
            for args in (("job", "old", "/files/out", "/files/stage.part"),
                         ("job", "new", "/files/wrong", "/files/stage.part"),
                         ("job", "new", "/files/out", "/files/wrong.part"),
                         ("other", "new", "/files/out", "/files/stage.part")):
                self.assertEqual(0, db.execute(publish, args).rowcount)
            self.assertEqual(1, db.execute(publish, ("job", "new", "/files/out", "/files/stage.part")).rowcount)
            self.assertEqual(0, db.execute(publish, ("job", "new", "/files/out", "/files/stage.part")).rowcount)
            self.assertEqual(("COMPLETED", None, "/files/out"),
                             db.execute("SELECT status,temp_path,destination_key FROM downloads").fetchone())

    def test_checkpoint_transaction_and_destination_reservation_survive_stop(self):
        with closing(sqlite3.connect(":memory:")) as db:
            create(db)
            with db:
                db.execute("INSERT INTO downloads (id,url,status,dest_path,destination_key,temp_path,attempt_token,downloaded_bytes) "
                           "VALUES ('job','fixture','PAUSED','/files/out','/files/out','/files/stage.part','current',2)")
                db.execute("INSERT INTO download_segments VALUES ('job',0,0,9,2,'PAUSED')")
            with self.assertRaises(sqlite3.IntegrityError), db:
                self.assertEqual(1, db.execute("UPDATE downloads SET downloaded_bytes=4 WHERE " + CHECKPOINT_WHERE,
                                               ("job", "current", "/files/stage.part")).rowcount)
                db.execute("DELETE FROM download_segments WHERE task_id='job'")
                db.execute("INSERT INTO download_segments VALUES ('job',0,0,9,4,'PAUSED')")
                db.execute("INSERT INTO download_segments VALUES ('job',0,0,9,3,'PAUSED')")
            self.assertEqual((2,), db.execute("SELECT downloaded_bytes FROM downloads").fetchone())
            self.assertEqual((2,), db.execute("SELECT current_byte FROM download_segments").fetchone())
            with self.assertRaises(sqlite3.IntegrityError), db:
                db.execute("INSERT INTO downloads (id,url,status,destination_key) VALUES ('other','fixture','PENDING','/files/out')")
            self.assertEqual(1, db.execute("UPDATE downloads SET status='PENDING',attempt_token=NULL WHERE " + ROW_WHERE + "=?",
                                           ("job", "PAUSED", "current")).rowcount)
            self.assertEqual(0, db.execute("UPDATE downloads SET status='CONNECTING' WHERE " + ROW_WHERE + "=?",
                                           ("job", "PENDING", "current")).rowcount)
            self.assertEqual(1, db.execute("UPDATE downloads SET status='CONNECTING',attempt_token='new' WHERE " + ROW_WHERE + " IS NULL",
                                           ("job", "PENDING")).rowcount)

    def test_provider_selects_only_the_committed_reservation_with_a_file_identity(self):
        with closing(sqlite3.connect(":memory:")) as db:
            create(db)
            db.execute("INSERT INTO downloads (id,url,status,dest_path,destination_key,temp_path,attempt_token,total_bytes,downloaded_bytes) "
                       "VALUES ('job','fixture','RUNNING','/files/out','/files/out','/files/stage.part','current',4,4)")
            select = "SELECT id FROM downloads WHERE " + SHAREABLE_WHERE
            self.assertIsNone(db.execute(select, ('job',)).fetchone())
            publish = "UPDATE downloads SET status='COMPLETED',temp_path=NULL,published_identity='stat' WHERE " + PUBLICATION_WHERE
            self.assertEqual(1, db.execute(publish, ('job', 'current', '/files/out', '/files/stage.part')).rowcount)
            self.assertEqual(('job',), db.execute(select, ('job',)).fetchone())
            self.assertIsNone(db.execute(select, ('not-the-job',)).fetchone())
            for column, value in (("attempt_token", None), ("destination_key", None), ("published_identity", None),
                                  ("temp_path", "/files/stage.part"), ("status", "FAILED"), ("downloaded_bytes", 1)):
                with self.subTest(column=column):
                    db.execute("SAVEPOINT altered")
                    db.execute("UPDATE downloads SET " + column + "=?", (value,))
                    self.assertIsNone(db.execute(select, ('job',)).fetchone())
                    db.execute("ROLLBACK TO altered")
                    db.execute("RELEASE altered")


if __name__ == "__main__":
    unittest.main()
