package com.privacy.browser;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owner-thread weighted LRU. Size is the allocation size, not the number of cards. */
final class TabPreviewCache<K, V> {
    private static final class Item<V> {
        final V value;
        final int bytes;
        Item(V value, int bytes) { this.value = value; this.bytes = bytes; }
    }

    private final int limit;
    private final LinkedHashMap<K, Item<V>> items = new LinkedHashMap<>(16, .75f, true);
    private int bytes;

    TabPreviewCache(int limit) { this.limit = limit; }

    V get(K key) {
        Item<V> item = items.get(key);
        return item == null ? null : item.value;
    }

    boolean put(K key, V value, int allocationBytes) {
        if (allocationBytes <= 0 || allocationBytes > limit) return false;
        remove(key);
        Iterator<Map.Entry<K, Item<V>>> oldest = items.entrySet().iterator();
        while (bytes > limit - allocationBytes) {
            bytes -= oldest.next().getValue().bytes;
            oldest.remove();
        }
        items.put(key, new Item<>(value, allocationBytes));
        bytes += allocationBytes;
        return true;
    }

    boolean remove(K key) {
        Item<V> item = items.remove(key);
        if (item == null) return false;
        bytes -= item.bytes;
        return true;
    }

    void clear() { items.clear(); bytes = 0; }
    int bytes() { return bytes; }
}
