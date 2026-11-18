"""Replay the assistant's legacy SQL operations on host SQLite, not Android SQLiteOpenHelper."""
import contextlib
from pathlib import Path
import re
import sqlite3
import unittest


ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/privacy/browser/ai'
HELPER = (ROOT / 'AgentDbHelper.java').read_text()
FILES = (ROOT / 'AgentLegacyFiles.java').read_text()


def sql_constant(name):
    return re.search(rf'\b{name} = "([^"\n]+)";', HELPER)[1]


def size_constant(source, name):
    return int(re.search(rf'\b{name} = ([\d_]+);', source)[1].replace('_', ''))


ADD_PATH = sql_constant('ADD_IMAGE_PATH')
CLEAR_CONTEXT = sql_constant('DROP_RETIRED_CONTEXT')
CLEAR_ARGS = sql_constant('CLEAR_RETIRED_ARGS')
MAX_NAME = size_constant(FILES, 'MAX_NAME_LENGTH')
MAX_REFERENCES = size_constant(HELPER, 'MAX_PROJECTED_ATTACHMENTS')
PREFIX, SUFFIX = re.search(r'String sql = "([^"\n]+)" \+ AgentLegacyFiles.MAX_NAME_LENGTH\s*\+ "([^"\n]+)"', HELPER).groups()
NAME_QUERY = PREFIX + str(MAX_NAME) + SUFFIX
MESSAGE_COLS = ('id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, type INTEGER NOT NULL, '
                'content TEXT, tool_name TEXT, tool_args TEXT, in_progress INTEGER NOT NULL DEFAULT 0, '
                'is_resolved INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL')
IMAGE = 'img_12345678-1234-1234-1234-000000000001.webp'


@contextlib.contextmanager
def version_one(has_image_path):
    with contextlib.closing(sqlite3.connect(':memory:')) as db:
        db.executescript(f'''
            CREATE TABLE sessions (session_id TEXT PRIMARY KEY, title TEXT NOT NULL, is_active INTEGER NOT NULL);
            CREATE TABLE messages ({MESSAGE_COLS}{', image_path TEXT' if has_image_path else ''});
            CREATE INDEX idx_msg_session ON messages(session_id);
            CREATE TABLE contexts (session_id TEXT PRIMARY KEY, raw_contents_json TEXT NOT NULL, pending_model_content TEXT);
            INSERT INTO sessions VALUES ('a', 'Old conversation', 1);
            INSERT INTO messages (session_id, type, content, tool_name, tool_args, created_at)
                VALUES ('a', 1, 'Original transcript', 'fixture-action', 'legacy args', 7);
            INSERT INTO contexts VALUES ('a', hex(zeroblob(1200000)), 'inert pending data');
        ''')
        yield db


def clean(db):
    # Python sqlite3 does not open a transaction automatically for DDL; match the Java helper.
    db.execute('BEGIN')
    with db:
        db.execute(CLEAR_CONTEXT)
        db.execute(CLEAR_ARGS)


class AssistantLegacySqlTest(unittest.TestCase):
    def test_add_column_does_not_drop_transcript_or_context_rows(self):
        with version_one(False) as db:
            db.execute(ADD_PATH)
            self.assertEqual((1, 'Original transcript', 'legacy args', None), db.execute(
                'SELECT id, content, tool_args, image_path FROM messages').fetchone())
            self.assertEqual(('Old conversation', 1), db.execute('SELECT title,is_active FROM sessions').fetchone())
            self.assertEqual((2_400_000,), db.execute('SELECT length(raw_contents_json) FROM contexts').fetchone())

    def test_explicit_cleanup_only_removes_retired_data_and_is_repeatable(self):
        with version_one(True) as db:
            db.execute('UPDATE messages SET image_path = ?', ('/fixture/agent_images/' + IMAGE,))
            db.commit()
            for _ in range(2):
                clean(db)
                self.assertEqual((), tuple(db.execute("SELECT name FROM sqlite_master WHERE name='contexts'")))
                self.assertEqual((1, 'Original transcript', 'fixture-action', None, '/fixture/agent_images/' + IMAGE),
                                 db.execute('SELECT id,content,tool_name,tool_args,image_path FROM messages').fetchone())
                self.assertEqual(('Old conversation',), db.execute('SELECT title FROM sessions').fetchone())

    def test_transaction_failure_preserves_context_and_argument_rows(self):
        with version_one(True) as db:
            db.execute("CREATE TRIGGER fail_write BEFORE UPDATE OF tool_args ON messages BEGIN SELECT RAISE(ABORT, 'fixture'); END")
            with self.assertRaises(sqlite3.IntegrityError):
                clean(db)
            self.assertEqual(('inert pending data',), db.execute('SELECT pending_model_content FROM contexts').fetchone())
            self.assertEqual(('legacy args',), db.execute('SELECT tool_args FROM messages').fetchone())

    def test_reference_query_returns_bounded_tails_for_both_legacy_aliases_and_large_rows(self):
        short = IMAGE.removesuffix('.webp') + '.png'
        with version_one(True) as db:
            db.execute('UPDATE messages SET image_path = ?', ('/old/agent_images/' + IMAGE,))
            db.executemany('INSERT INTO messages(session_id,type,image_path,created_at) VALUES (?,1,?,1)',
                           [('b', '/another/private/alias/agent_images/' + IMAGE + '/'),
                            ('b', '/fixture/' + 'x' * 2_300_000 + '/' + short)])
            self.assertEqual({IMAGE, '/' + short}, {row[0] for row in db.execute(NAME_QUERY + f' LIMIT {MAX_REFERENCES + 1}')})
            self.assertEqual([(IMAGE,)], db.execute(NAME_QUERY + ' AND session_id=? LIMIT 10', ('a',)).fetchall())
            self.assertEqual(1, len(db.execute(NAME_QUERY + ' LIMIT 1').fetchall()))

    def test_transcript_projection_avoids_transporting_old_multi_megabyte_columns(self):
        with version_one(True) as db:
            length = size_constant(HELPER, 'MESSAGE_EXCERPT_CHARS')
            db.execute('UPDATE messages SET content=hex(zeroblob(1200000)), image_path=hex(zeroblob(1200000))')
            projection = [f'substr(content, 1, {length + 1})', 'CASE WHEN length(image_path) <= 1024 THEN image_path ELSE NULL END']
            value, path = db.execute('SELECT ' + ', '.join(projection) + ' FROM messages').fetchone()
            self.assertEqual(length + 1, len(value))
            self.assertIsNone(path)
            self.assertEqual((2_400_000, 2_400_000), db.execute('SELECT length(content),length(image_path) FROM messages').fetchone())


if __name__ == '__main__':
    unittest.main()
