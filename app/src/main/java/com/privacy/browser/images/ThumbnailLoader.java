package com.privacy.browser.images;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** UI-owned cache and read admission, with decoding on an ordered background executor. */
final class ThumbnailLoader<K, V> {
    private static final int MAX_CACHE_KEYS = 128;
    private static final int MAX_READS = 24;
    private static final int MAX_WAITERS_PER_KEY = 24;

    private final Executor disk;
    private final Executor ui;
    private final Function<K, V> decode;
    private final ToIntFunction<V> weight;
    private final int byteBudget;
    private final Map<K, Read> pending = new LinkedHashMap<>();
    private final Map<K, Cached<V>> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long cachedBytes;
    private int reads;

    ThumbnailLoader(Executor disk, Executor ui, Function<K, V> decode, ToIntFunction<V> weight, int byteBudget) {
        this.disk = disk;
        this.ui = ui;
        this.decode = decode;
        this.weight = weight;
        this.byteBudget = byteBudget;
    }

    V peek(K key) {
        Cached<V> hit = cache.get(key);
        return hit == null ? null : hit.value;
    }

    /** Cache hits and admission failures settle on the UI caller; disk results return on the UI executor. */
    void load(K key, Consumer<V> ready) {
        Cached<V> hit = cache.get(key);
        if (hit != null) {
            ready.accept(hit.value);
            return;
        }
        Read existing = pending.get(key);
        if (existing != null) {
            if (existing.waiters.size() < MAX_WAITERS_PER_KEY) existing.waiters.add(ready);
            else ready.accept(null);
            return;
        }
        if (reads >= MAX_READS) {
            ready.accept(null);
            return;
        }
        Read read = new Read(key, ready);
        pending.put(key, read);
        reads++;
        try {
            disk.execute(read::run);
        } catch (RejectedExecutionException rejected) {
            pending.remove(key);
            reads--;
            ready.accept(null);
        }
    }

    /** Drop images and pending deliveries for changed files, without walking a potentially large deletion set. */
    void invalidate(Set<K> keys) {
        Iterator<Map.Entry<K, Cached<V>>> cached = cache.entrySet().iterator();
        while (cached.hasNext()) {
            Map.Entry<K, Cached<V>> entry = cached.next();
            if (keys.contains(entry.getKey())) {
                cachedBytes -= entry.getValue().bytes;
                cached.remove();
            }
        }
        Iterator<Map.Entry<K, Read>> reading = pending.entrySet().iterator();
        while (reading.hasNext()) {
            Map.Entry<K, Read> entry = reading.next();
            if (keys.contains(entry.getKey())) {
                Read read = entry.getValue();
                read.invalidated = true;
                read.waiters.clear();
                reading.remove();
            }
        }
    }

    private void complete(Read read, V value) {
        reads--;
        if (pending.get(read.key) != read) return;
        int bytes = value == null ? 0 : weight.applyAsInt(value);
        if (bytes <= byteBudget) {
            cache.put(read.key, new Cached<>(value, bytes));
            cachedBytes += bytes;
            Iterator<Cached<V>> oldest = cache.values().iterator();
            while (cachedBytes > byteBudget || cache.size() > MAX_CACHE_KEYS) {
                cachedBytes -= oldest.next().bytes;
                oldest.remove();
            }
        }
        // Leave the read discoverable during dispatch so reentrant save/reset can invalidate later waiters.
        try {
            for (int i = 0; i < read.waiters.size(); i++) read.waiters.get(i).accept(value);
        } finally {
            pending.remove(read.key, read);
        }
    }

    private final class Read {
        final K key;
        final List<Consumer<V>> waiters = new ArrayList<>();
        volatile boolean invalidated;

        Read(K key, Consumer<V> ready) {
            this.key = key;
            waiters.add(ready);
        }

        void run() {
            V value = invalidated ? null : decode.apply(key);
            ui.execute(() -> complete(this, value));
        }
    }

    private static final class Cached<V> {
        final V value;
        final int bytes;

        Cached(V value, int bytes) {
            this.value = value;
            this.bytes = bytes;
        }
    }
}
