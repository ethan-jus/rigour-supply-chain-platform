package com.rigour.tenant.iam.infrastructure.persistence.settings;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.function.Supplier;

/** 只存不可变授权定义；调用方每次读取数据库版本，实时身份/资格不能放入此缓存。 */
final class VersionedIamReadCache<K, V> {
    private record Entry<V>(long version, long savedAt, V value) {}
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final int capacity;
    private final long ttlMillis;
    private final Clock clock;

    VersionedIamReadCache() { this(1024, Duration.ofMinutes(1), Clock.systemUTC()); }

    VersionedIamReadCache(int capacity, Duration ttl, Clock clock) {
        this.capacity = capacity;
        this.ttlMillis = ttl.toMillis();
        this.clock = clock;
    }

    V get(K key, long version, Supplier<V> load) {
        synchronized (entries) {
            var entry = entries.get(key);
            long now = clock.millis();
            if (entry != null && entry.version() == version
                    && now >= entry.savedAt() && now - entry.savedAt() < ttlMillis)
                return entry.value();
        }
        // No global lock or held JDBC connection while another key loads. Failures are never cached.
        V value = load.get();
        synchronized (entries) {
            entries.put(key, new Entry<>(version, clock.millis(), value));
            while (entries.size() > capacity) entries.remove(entries.keySet().iterator().next());
        }
        return value;
    }
}
