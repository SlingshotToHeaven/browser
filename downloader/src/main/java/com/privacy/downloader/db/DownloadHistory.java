package com.privacy.downloader.db;

import com.privacy.downloader.model.DownloadStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Bound parameters and a stable keyset for the Downloads screen. Live progress isn't a paging key. */
public final class DownloadHistory {
    public static final int PAGE_SIZE = 48;
    public static final int ID_BATCH = 128;
    static final String AUTOMATIC = "status IN ('PENDING','CONNECTING','RUNNING','WAITING_FOR_WIFI','WAITING_FOR_BATTERY','WAITING_FOR_SCHEDULE')";
    static final String INCOMPLETE_LINK = "status NOT IN ('COMPLETED','CANCELLED') AND substr(url,1,5)<>'blob:'";
    static final String DATE = "coalesce(created_time,0)";
    static final String SIZE = "CASE WHEN total_bytes>0 THEN total_bytes ELSE coalesce(downloaded_bytes,0) END";
    static final String NAME = "name_order";
    static final String STATUS_INDEX = "CREATE INDEX downloads_status_date ON downloads (status, " + DATE + ", id)";
    static final String DATE_INDEX = "CREATE INDEX downloads_date ON downloads (" + DATE + ", id)";
    static final String SIZE_INDEX = "CREATE INDEX downloads_size ON downloads (" + SIZE + ", id)";
    static final String NAME_INDEX = "CREATE INDEX downloads_name ON downloads (" + NAME + ", id)";
    static final String COMPLETED_SIZE_INDEX = "CREATE INDEX downloads_completed_size ON downloads (" + SIZE + ", id) WHERE status='COMPLETED'";
    static final String COMPLETED_NAME_INDEX = "CREATE INDEX downloads_completed_name ON downloads (" + NAME + ", id) WHERE status='COMPLETED'";
    static final String DUPLICATE_INDEX = "CREATE INDEX downloads_incomplete_name ON downloads (" + NAME + ", " + DATE
            + " DESC, id DESC) WHERE " + INCOMPLETE_LINK;
    static final String DUPLICATE_SQL = "SELECT id, downloaded_bytes FROM downloads WHERE " + INCOMPLETE_LINK
            + " AND id IS NOT NULL AND " + NAME + "=? ORDER BY " + DATE + " DESC, id DESC LIMIT 1";
    static final String CARD_COLUMNS = "id, attempt_token, file_name, status, total_bytes, downloaded_bytes, num_segments, error_message, "
            + DATE + ", " + NAME;

    private DownloadHistory() {}

    public enum Sort {
        NEWEST(DATE, true), OLDEST(DATE, false), LARGEST(SIZE, true), SMALLEST(SIZE, false),
        NAME_ASC(NAME, false), NAME_DESC(NAME, true);

        final String sql;
        final boolean desc;

        Sort(String sql, boolean desc) { this.sql = sql; this.desc = desc; }

        public int compare(Row first, Row second) {
            int value;
            if (sql.equals(NAME)) value = compareText(first.nameKey, second.nameKey);
            else if (sql.equals(SIZE)) value = Long.compare(first.sizeKey(), second.sizeKey());
            else value = Long.compare(first.created, second.created);
            if (value == 0) value = compareText(first.id, second.id);
            return desc ? -value : value;
        }

        String key(Row row) {
            return sql.equals(NAME) ? row.nameKey : Long.toString(sql.equals(SIZE) ? row.sizeKey() : row.created);
        }
    }

    public enum Filter {
        ALL, ACTIVE, COMPLETED, VIDEOS(".mp4", ".mkv", ".webm", ".avi", ".mov"),
        MUSIC(".mp3", ".m4a", ".flac", ".wav", ".ogg"),
        DOCS(".pdf", ".doc", ".docx", ".txt", ".epub"), ARCHIVES(".zip", ".7z", ".rar", ".tar", ".gz", ".apk");

        final String[] suffixes;
        Filter(String... suffixes) { this.suffixes = suffixes; }

        public boolean hasExtension(String name) {
            String lower = searchKey(name);
            for (String suffix : suffixes) if (lower.endsWith(suffix)) return true;
            return false;
        }
    }

    public static final class Query {
        public final Filter filter;
        public final Sort sort;
        public final String search;

        public Query(Filter filter, Sort sort, String search) {
            this.filter = filter;
            this.sort = sort;
            this.search = searchKey(search == null ? null : search.trim());
        }

        Sql page(Row after) {
            List<String> args = new ArrayList<>();
            String direction = sort.desc ? " DESC" : " ASC";
            String order = " ORDER BY " + sort.sql + direction + ", id" + direction;
            String limit = " LIMIT " + (PAGE_SIZE + 1);
            String from = " FROM downloads INDEXED BY " + pageIndex() + " WHERE ";
            String where = where(args);
            if (after == null) return new Sql("SELECT " + CARD_COLUMNS + from + where + order + limit, args);

            // Seek in the tie group by ID, then among strictly later sort keys. A tuple/OR alone
            // can make SQLite 3.18 walk the whole equal-size/name group from the start on every page.
            String op = sort.desc ? "<" : ">";
            String bind = sort.sql.equals(NAME) ? "?" : "(?+0)"; // Android binds numeric arguments as text.
            String key = sort.key(after);
            String select = "SELECT " + CARD_COLUMNS + ", " + sort.sql + " AS seek_key" + from;
            String tied = select + where + " AND " + sort.sql + "=" + bind + " AND id" + op + "? ORDER BY id" + direction + limit;
            Collections.addAll(args, key, after.id);
            String rest = select + where(args) + " AND " + sort.sql + op + bind + order + limit;
            args.add(key);
            return new Sql("SELECT * FROM (" + tied + ") UNION ALL SELECT * FROM (" + rest + ") ORDER BY seek_key"
                    + direction + ", id" + direction + limit, args);
        }

        private String pageIndex() {
            if (filter == Filter.ACTIVE) return "downloads_status_date";
            if (sort.sql.equals(NAME)) return filter == Filter.COMPLETED ? "downloads_completed_name" : "downloads_name";
            if (sort.sql.equals(SIZE)) return filter == Filter.COMPLETED ? "downloads_completed_size" : "downloads_size";
            return filter == Filter.COMPLETED ? "downloads_status_date" : "downloads_date";
        }

        Sql ids(List<String> ids, boolean cards) {
            List<String> args = new ArrayList<>();
            String where = where(args);
            if (ids != null) {
                where += " AND id IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")";
                args.addAll(ids);
            }
            // With IN (...) and a status filter the planner can otherwise walk all rows of that status.
            String table = ids == null ? "downloads" : "downloads INDEXED BY sqlite_autoindex_downloads_1";
            return new Sql("SELECT " + (cards ? CARD_COLUMNS : "id") + " FROM " + table + " WHERE " + where, args);
        }

        private String where(List<String> args) {
            StringBuilder where = new StringBuilder("id IS NOT NULL");
            if (!search.isEmpty()) {
                where.append(" AND instr(name_search, ?) > 0");
                args.add(search);
            }
            if (filter == Filter.ACTIVE) where.append(" AND ").append(AUTOMATIC);
            else if (filter == Filter.COMPLETED) where.append(" AND status='COMPLETED'");
            else if (filter.suffixes.length > 0) {
                List<String> extensions = new ArrayList<>();
                for (String suffix : filter.suffixes) {
                    extensions.add("substr(name_search, -" + suffix.length() + ")=?");
                    args.add(suffix);
                }
                where.append(" AND (").append(String.join(" OR ", extensions)).append(")");
            }
            return where.toString();
        }

    }

    static final class Sql {
        final String text;
        final String[] args;
        Sql(String text, List<String> args) { this.text = text; this.args = args.toArray(new String[0]); }
    }

    public static final class Row {
        public final String id;
        public final String token;
        public final String name;
        public final DownloadStatus status;
        public final long total;
        public final long downloaded;
        public final int threads;
        public final String reason;
        public final long created;
        public final String nameKey;

        public Row(String id, String token, String name, DownloadStatus status, long total, long downloaded,
                   int threads, String reason, long created, String nameKey) {
            this.id = id;
            this.token = token;
            this.name = name;
            this.status = status;
            this.total = total;
            this.downloaded = downloaded;
            this.threads = threads;
            this.reason = reason;
            this.created = created;
            this.nameKey = nameKey;
        }

        long sizeKey() { return total > 0 ? total : downloaded; }

        @Override public boolean equals(Object object) {
            if (!(object instanceof Row)) return false;
            Row other = (Row) object;
            return id.equals(other.id) && Objects.equals(token, other.token) && Objects.equals(name, other.name)
                    && status == other.status && total == other.total && downloaded == other.downloaded && threads == other.threads
                    && Objects.equals(reason, other.reason) && created == other.created && nameKey.equals(other.nameKey);
        }

        @Override public int hashCode() {
            return Objects.hash(id, token, name, status, total, downloaded, threads, reason, created, nameKey);
        }
    }

    public static final class Page {
        public final List<Row> rows;
        public final boolean more;
        Page(List<Row> rows, boolean more) { this.rows = Collections.unmodifiableList(rows); this.more = more; }
    }

    public static final class Duplicate {
        public final String id;
        public final long savedBytes;
        Duplicate(String id, long savedBytes) { this.id = id; this.savedBytes = savedBytes; }
    }

    static String searchKey(String name) { return name == null ? "" : name.toLowerCase(Locale.US); }

    // Match Java's simple case-insensitive comparison, including dotted/dotless i and sigma.
    static String nameKey(String name) {
        if (name == null) return "";
        StringBuilder key = new StringBuilder(name.length());
        name.codePoints().forEach(cp -> key.appendCodePoint(Character.toLowerCase(Character.toUpperCase(cp))));
        return key.toString();
    }

    // SQLite BINARY order on UTF-8 compares code points, not Java UTF-16 code units.
    private static int compareText(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int x = a.codePointAt(i), y = b.codePointAt(j);
            if (x != y) return Integer.compare(x, y);
            i += Character.charCount(x);
            j += Character.charCount(y);
        }
        return Integer.compare(a.length(), b.length());
    }
}
