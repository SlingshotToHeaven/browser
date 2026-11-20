"""Host checks of the device harness. They do not substitute for installed Android evidence."""
from email.message import Message
from hashlib import sha256
import sqlite3
from tempfile import TemporaryDirectory
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from xml.etree import ElementTree as ET

from test_download_queue_sql import create
from tools.check_android_download_upgrade import (
    CheckFailed, JOBS, LEGACY, db_copy, find_card, fixture, fixture_id, installed_apk_path,
    read_profile, seed_rows, validate_receipt, wire_reply,
)


class AndroidDownloadUpgradeHarnessTest(unittest.TestCase):
    def test_api_34_base_apk_path_is_randomized_and_bound_to_package(self):
        path = "/data/app/~~A_ran-domDirNonceValue==/com.browser-anotherNonce_value==/base.apk"
        self.assertEqual(installed_apk_path("package:" + path, "com.browser"), path)
        for wrong in (path.replace("com.browser-", "com.other-"), path.replace("~~", "../"),
                      path + "\npackage:/data/app/other/split.apk"):
            with self.assertRaises(CheckFailed):
                installed_apk_path("package:" + wrong, "com.browser")

    def test_http_fixture_only_serves_the_exact_checkpoint_suffix(self):
        active = JOBS["active"]
        with fixture() as server:
            url = server.url("active")
            headers = {"Range": f"bytes={active.offset}-{len(active.body) - 1}", "If-Range": active.etag}
            server.gates["active"].set()
            with urlopen(Request(url, headers=headers), timeout=3) as reply:
                self.assertEqual(reply.status, 206)
                self.assertEqual(reply.headers["Content-Range"], f"bytes {active.offset}-{len(active.body) - 1}/{len(active.body)}")
                self.assertEqual(sha256(reply.read()).digest(), sha256(active.body[active.offset:]).digest())
            for changed in ({"Range": f"bytes=0-{len(active.body) - 1}"}, {"If-Range": '"stale"'},
                            {"User-Agent": LEGACY["user_agent"]}, {"Cookie": LEGACY["cookies"]},
                            {"Referer": LEGACY["referrer"]}, {"Authorization": "fixture"}):
                with self.assertRaises(HTTPError) as denied:
                    urlopen(Request(url, headers=headers | changed), timeout=3)
                self.assertEqual(denied.exception.code, 400)
                denied.exception.close()
            self.assertEqual(server.events(), [("active", "range")] + [("active", "wrong-range")] * 2
                             + [("active", "credentials")] * 4)

    def test_duplicate_range_does_not_match(self):
        history = JOBS["history"]
        headers = Message()
        headers["Range"] = "bytes=0-0"
        self.assertEqual(wire_reply(history, "GET", headers), ("probe", (0, 0)))
        headers["Range"] = "bytes=0-0"
        self.assertEqual(wire_reply(history, "GET", headers), ("wrong-range", None))
        self.assertEqual(wire_reply(history, "HEAD", headers), ("unexpected", None))

    def test_seed_preserves_real_existing_row_and_does_not_apply_migration(self):
        with TemporaryDirectory() as work:
            source = work + "/source.db"
            db = sqlite3.connect(source)
            try:
                create(db, initial_version=7)
                db.execute("PRAGMA user_version=7")
                history = JOBS["history"]
                dest = "/data/user/0/com.browser/files/download-publication/" + history.name
                db.execute("INSERT INTO downloads (id,url,file_name,dest_path,destination_key,status,created_time) "
                           "VALUES (?,?,?,?,?,'COMPLETED',1)", (fixture_id("history"), "http://127.0.0.1/old", history.name, dest, dest.lower()))
                db.commit()
            finally:
                db.close()
            with open(source, "rb") as file:
                images = (file.read(), b"")
            with db_copy(images, work, writable=True) as (copy, _):
                before = read_profile(copy)
                seed_rows(copy, before, "http://127.0.0.1")
                after = read_profile(copy)
                self.assertEqual(after.schema, 7)
                self.assertEqual(after.rows["history"], before.rows["history"])
                self.assertEqual(after.indexes, before.indexes)
                self.assertEqual(after.rows["queued"]["status"], "PENDING")
                self.assertIsNone(after.rows["queued"]["attempt_token"])
                self.assertEqual(after.segments["queued"], [])
                for role, status in (("paused", "PAUSED"), ("active", "RUNNING")):
                    entry = JOBS[role]
                    row = after.rows[role]
                    self.assertEqual(row["status"], status)
                    self.assertEqual(row["downloaded_bytes"], entry.offset)
                    self.assertEqual(row["verified_range_url"], row["url"])
                    self.assertEqual(row["expected_sha256"], entry.digest)
                    self.assertEqual({k: row[k] for k in LEGACY}, LEGACY)
                    self.assertEqual(after.segments[role], [(0, 0, len(entry.body) - 1, entry.offset, status)])
                with self.assertRaises(CheckFailed):
                    seed_rows(copy, after, "http://127.0.0.1")

    def test_badge_and_resume_button_belong_to_the_named_row(self):
        def xml_card(name, status, button):
            return (f'<node><node enabled="true" package="com.browser" resource-id="com.browser:id/card_text_filename" text="{name}"/>'
                    f'<node enabled="true" package="com.browser" resource-id="com.browser:id/card_text_status_badge" text="{status}"/>'
                    + ('<node enabled="true" package="com.browser" resource-id="com.browser:id/card_btn_pause_resume" />' if button else '')
                    + '</node>')
        root = ET.fromstring('<hierarchy>' + xml_card(JOBS["history"].name, "COMPLETED", False)
                             + xml_card(JOBS["paused"].name, "PAUSED", True) + '</hierarchy>')
        self.assertIsNotNone(find_card(root, "history", badge="COMPLETED"))
        self.assertIsNone(find_card(root, "history", "card_btn_pause_resume"))
        self.assertIsNone(find_card(root, "paused", badge="COMPLETED"))
        self.assertIsNotNone(find_card(root, "paused", "card_btn_pause_resume", "PAUSED"))

    def test_recipient_requires_exact_bytes_and_no_unselected_or_write_grants(self):
        denied = {"read": "denied", "write": "denied", "readGrant": False, "writeGrant": False}
        entry = JOBS["history"]
        access = {k: dict(denied) for k in ("history", "active", "queued", "staging", "stale", "descendant")}
        access["history"].update(read="opened", readGrant=True, name=entry.name, bytes=len(entry.body), sha256=entry.digest)
        receipt = {"uid": 10201, "action": "android.intent.action.SEND", "selected": ["history"],
                   "privateStage": "denied", "access": access}
        validate_receipt(receipt, 10201, ["history"], "SEND")
        with self.assertRaises(CheckFailed):
            validate_receipt(receipt, 10201, ["history"], "VIEW")
        with self.assertRaises(CheckFailed):
            validate_receipt(receipt, 10201, ["history"], unavailable=["history"])
        access["history"]["read"] = "missing"
        validate_receipt(receipt, 10201, ["history"], unavailable=["history"])
        access["history"]["readGrant"] = False
        with self.assertRaises(CheckFailed):
            validate_receipt(receipt, 10201, ["history"], unavailable=["history"])
        access["history"]["readGrant"] = True
        for label, key in (("queued", "readGrant"), ("history", "writeGrant"), ("staging", "readGrant")):
            access[label][key] = True
            with self.assertRaises(CheckFailed):
                validate_receipt(receipt, 10201, ["history"], unavailable=["history"])
            access[label][key] = False


if __name__ == "__main__":
    unittest.main()
