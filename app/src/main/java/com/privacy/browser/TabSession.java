package com.privacy.browser;

import com.privacy.downloader.net.RequestContext;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable tab recovery metadata. None of it authorizes a live document or a private view. */
final class TabSession {
    static final int MAX_TABS = 512;
    static final int MAX_URL = 8192;
    static final int MAX_STATE = 4 * 1024 * 1024;
    static final int MAX_INDEX = 8 * 1024 * 1024;
    static final int MAX_BATCH = 16 * 1024 * 1024;
    static final int VERSION = 1;

    static final class Row {
        final String id, url, title, state;
        final boolean desktop, rendererLost;

        Row(String id, String url, String title, boolean desktop, boolean rendererLost, String state) {
            this.id = id;
            this.url = recoveryUrl(url);
            this.title = title == null || title.trim().isEmpty() ? "New Tab" : title.substring(0, Math.min(512, title.length()));
            this.desktop = desktop;
            this.rendererLost = rendererLost;
            this.state = rendererLost || !Objects.equals(this.url, url) ? null : state;
        }

        Row withState(String token) { return new Row(id, url, title, desktop, rendererLost, token); }
        boolean compatible(Row other) {
            return !rendererLost && !other.rendererLost && desktop == other.desktop && url.equals(other.url);
        }

        String filename() { return state == null ? null : "state-" + id + "-" + state + ".bin"; }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Row)) return false;
            Row r = (Row) other;
            return id.equals(r.id) && url.equals(r.url) && title.equals(r.title) && Objects.equals(state, r.state)
                    && desktop == r.desktop && rendererLost == r.rendererLost;
        }

        @Override public int hashCode() { return Objects.hash(id, url, title, state, desktop, rendererLost); }
    }

    static final class Index {
        final List<Row> tabs;
        final String selected;

        Index(List<Row> tabs, String selected) {
            this.tabs = Collections.unmodifiableList(new ArrayList<>(tabs));
            this.selected = tabs.stream().anyMatch(row -> row.id.equals(selected)) ? selected
                    : tabs.isEmpty() ? null : tabs.get(0).id;
        }

        @Override public boolean equals(Object other) {
            return other instanceof Index && tabs.equals(((Index) other).tabs)
                    && Objects.equals(selected, ((Index) other).selected);
        }

        @Override public int hashCode() { return Objects.hash(tabs, selected); }
    }

    /** A URL-only row did not request a blob, unlike a missing or unusable saved generation. */
    static final class StateRead {
        enum Status { URL_ONLY, LOADED, MISSING, CORRUPT, STALE }
        static final StateRead URL_ONLY = new StateRead(Status.URL_ONLY, null);
        static final StateRead MISSING = new StateRead(Status.MISSING, null);
        static final StateRead CORRUPT = new StateRead(Status.CORRUPT, null);
        static final StateRead STALE = new StateRead(Status.STALE, null);
        final Status status;
        final byte[] bytes;

        private StateRead(Status status, byte[] bytes) {
            this.status = status;
            this.bytes = bytes;
        }

        static StateRead loaded(byte[] bytes) { return new StateRead(Status.LOADED, bytes); }
    }

    /** Byte arrays are transferred by the producer, never mutated after handoff. */
    static final class Frame {
        final Index index;
        final Map<String, byte[]> states;

        Frame(Index index, Map<String, byte[]> states) {
            this.index = index;
            this.states = Collections.unmodifiableMap(new LinkedHashMap<>(states));
        }

        byte[] state(Row row) { return states.get(row.filename()); }

        Frame after(Frame earlier) {
            if (earlier.states.isEmpty()) return this;
            Map<String, byte[]> merged = new LinkedHashMap<>();
            int budget = MAX_BATCH;
            // Prefer fresh captures, then retain in-flight bytes only for references still live in the next index.
            for (Row row : index.tabs) {
                byte[] bytes = state(row);
                if (bytes != null && bytes.length <= budget) {
                    merged.put(row.filename(), bytes);
                    budget -= bytes.length;
                }
            }
            for (Row row : index.tabs) {
                String file = row.filename();
                if (file == null || states.containsKey(file)) continue;
                byte[] bytes = earlier.state(row);
                if (bytes != null && bytes.length <= budget) {
                    merged.put(file, bytes);
                    budget -= bytes.length;
                }
            }
            return new Frame(index, merged);
        }
    }

    static final Index EMPTY = new Index(Collections.emptyList(), null);

    private TabSession() {}

    static Row regular(String id, String url, String title, boolean desktop, boolean rendererLost, boolean privateTab) {
        return privateTab ? null : new Row(id, url, title, desktop, rendererLost, null);
    }

    static String recoveryUrl(String url) {
        url = InternalHome.migrate(url);
        if (url == null || url.length() > MAX_URL || RequestContext.origin(url) == null
                || (InternalHome.ownsHost(url) && !InternalHome.isHome(url))) return InternalHome.URL;
        return url;
    }

    static boolean isId(String value) {
        if (value == null || value.length() != 36) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    static Index fromLegacy(String json, int selectedPosition) throws JSONException {
        if (json == null) return EMPTY;
        if (json.length() > MAX_INDEX) throw new JSONException("Legacy session is too large");
        JSONArray saved = new JSONArray(json);
        if (saved.length() > MAX_TABS) throw new JSONException("Too many saved tabs");
        List<Row> rows = new ArrayList<>();
        String selected = null;
        int position = Math.min(Math.max(0, selectedPosition), saved.length() - 1);
        for (int i = 0; i < saved.length(); i++) {
            JSONObject old = saved.optJSONObject(i);
            if (old == null || old.optBoolean("incognito") || old.optBoolean("private")) continue;
            // Positional blobs cannot prove identity, even when two tabs have the same URL.
            String id = UUID.randomUUID().toString();
            rows.add(new Row(id, old.optString("url"), old.optString("title"), old.optBoolean("desktop"),
                    old.optBoolean("renderer_lost"), null));
            if (i == position) selected = id;
        }
        return new Index(rows, selected);
    }

    static String encode(Index index) throws JSONException {
        if (index.tabs.size() > MAX_TABS) throw new JSONException("Too many saved tabs");
        JSONArray tabs = new JSONArray();
        Set<String> ids = new HashSet<>();
        for (Row row : index.tabs) {
            if (!isId(row.id) || !ids.add(row.id) || (row.state != null && !isId(row.state))) {
                throw new JSONException("Invalid tab identity");
            }
            tabs.put(new JSONObject().put("id", row.id).put("url", row.url).put("title", row.title)
                    .put("desktop", row.desktop).put("renderer_lost", row.rendererLost).put("state", row.state));
        }
        return new JSONObject().put("version", VERSION).put("selected", index.selected).put("tabs", tabs).toString();
    }

    static Index decode(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (root.optInt("version") != VERSION) throw new JSONException("Unknown session index version");
        JSONArray tabs = root.getJSONArray("tabs");
        if (tabs.length() > MAX_TABS) throw new JSONException("Too many saved tabs");
        List<Row> rows = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < tabs.length(); i++) {
            JSONObject row = tabs.getJSONObject(i);
            String id = row.getString("id");
            if (!isId(id) || !ids.add(id)) throw new JSONException("Invalid tab identity");
            if (row.optBoolean("incognito") || row.optBoolean("private")) continue;
            String state = row.optString("state", null);
            rows.add(new Row(id, row.getString("url"), row.getString("title"), row.optBoolean("desktop"),
                    row.optBoolean("renderer_lost"), isId(state) ? state : null));
        }
        return new Index(rows, root.optString("selected", null));
    }
}
