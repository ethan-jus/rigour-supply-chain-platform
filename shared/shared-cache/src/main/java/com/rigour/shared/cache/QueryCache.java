package com.rigour.shared.cache;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Bounded cache-aside. Authorization is resolved by the owning service before every lookup. */
public final class QueryCache {
    private final CacheStore store;
    private final CacheVersions versions;
    private final Supplier<Object> authority;
    private final BooleanSupplier bypass;
    private final MeterRegistry metrics;
    private final JsonMapper json = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private final Semaphore loaders;
    private final ThreadLocal<Boolean> loading = ThreadLocal.withInitial(() -> false);
    private final String namespace;
    private final int maxBytes;
    private final ConcurrentMap<String, CompletableFuture<byte[]>> pending = new ConcurrentHashMap<>();
    private volatile long unavailableUntil;

    public QueryCache(CacheStore store, CacheVersions versions, Supplier<Object> authority,
                      BooleanSupplier bypass, MeterRegistry metrics, String namespace,
                      int maxBytes, int maxLoads) {
        this.store = store;
        this.versions = versions;
        this.authority = authority;
        this.bypass = bypass;
        this.metrics = metrics;
        this.namespace = namespace;
        this.maxBytes = maxBytes;
        this.loaders = new Semaphore(maxLoads);
    }

    public static QueryCache disabled() {
        return new QueryCache(null, null, () -> null, () -> true, null, "disabled", 0, 16);
    }

    public void changed(String tenant) {
        if (versions != null) versions.changed(tenant);
    }

    public <T> T read(String tenant, String region, Object selection, long seconds,
                      TypeReference<T> type, Supplier<T> loader) {
        if (store == null) return loader.get();
        // Do not cache uncommitted results, internal jobs without an authority, or exports.
        Object scope = authority.get();
        if (scope == null || versions.dirty(tenant)) return loader.get();
        long version;
        try { version = versions.current(tenant); }
        catch (RuntimeException ex) { count(region, "version_unavailable"); return bounded(loader); }
        String key = namespace + ":" + region + ":" + version + ":" + digest(Arrays.asList(scope, selection));
        boolean force = bypass.getAsBoolean();
        if (!force) {
            byte[] found = get(tenant, key, region);
            T value = decode(found, type, region);
            if (value != null) { count(region, "hit"); return value; }
        }
        count(region, "miss");
        String flightKey = tenant + ":" + key + (force ? ":refresh" : "");
        var flight = new CompletableFuture<byte[]>();
        var running = pending.putIfAbsent(flightKey, flight);
        if (running != null) {
            try {
                T result = decode(running.get(30, TimeUnit.SECONDS), type, region);
                if (result != null) return result;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("查询已中断", ex);
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof RuntimeException cause) throw cause;
                throw new IllegalStateException(ex.getCause());
            } catch (TimeoutException ex) { throw new QueryBusyException(); }
            return bounded(loader);
        }
        String token = UUID.randomUUID().toString();
        boolean locked = false;
        try {
            if (System.nanoTime() >= unavailableUntil) {
                try {
                    locked = store.tryLock(tenant, key, token, Duration.ofSeconds(30));
                    if (!locked && !force) {
                        for (int attempt = 0; attempt < 8; attempt++) {
                            Thread.sleep(25);
                            byte[] bytes = get(tenant, key, region);
                            T value = decode(bytes, type, region);
                            if (value != null) { flight.complete(bytes); return value; }
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("查询已中断", ex);
                } catch (RuntimeException ex) { unavailable(region); }
            }
            long started = System.nanoTime();
            T value = bounded(loader);
            if (metrics != null) metrics.timer("rigour.query.cache.load", "region", region)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            byte[] bytes = null;
            try {
                if (value != null) bytes = json.writeValueAsBytes(value);
                if (bytes != null && bytes.length <= maxBytes && !versions.dirty(tenant)
                        && versions.current(tenant) == version && System.nanoTime() >= unavailableUntil) {
                    // Jitter only shortens TTL: freshness must never exceed the advertised ceiling.
                    long ttl = Math.max(1, seconds * 1000 - ThreadLocalRandom.current().nextLong(Math.max(1, seconds * 100)));
                    store.put(tenant, key, encode(bytes), Duration.ofMillis(ttl));
                    count(region, "put");
                } else if (bytes != null && bytes.length > maxBytes) count(region, "oversize");
            } catch (RuntimeException | IOException ex) { unavailable(region); }
            flight.complete(bytes);
            return value;
        } catch (RuntimeException | Error ex) {
            flight.completeExceptionally(ex);
            throw ex;
        } finally {
            pending.remove(flightKey, flight);
            if (locked) try { store.unlock(tenant, key, token); }
            catch (RuntimeException ex) { unavailable(region); }
        }
    }

    private <T> T bounded(Supplier<T> loader) {
        if (loading.get()) return loader.get();
        boolean acquired = false;
        try {
            acquired = loaders.tryAcquire(2, TimeUnit.SECONDS);
            if (!acquired) throw new QueryBusyException();
            loading.set(true);
            return loader.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new QueryBusyException();
        } finally { loading.remove(); if (acquired) loaders.release(); }
    }

    private byte[] get(String tenant, String key, String region) {
        if (System.nanoTime() < unavailableUntil) return null;
        try { return store.get(tenant, key).orElse(null); }
        catch (RuntimeException ex) { unavailable(region); return null; }
    }

    private <T> T decode(byte[] bytes, TypeReference<T> type, String region) {
        if (bytes == null) return null;
        try {
            if (bytes.length >= 2 && bytes[0] == (byte) 0x1f && bytes[1] == (byte) 0x8b) {
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                    bytes = gzip.readNBytes(maxBytes + 1);
                }
            }
            if (bytes.length > maxBytes) throw new IllegalArgumentException("Cache entry exceeds limit");
            return json.readValue(bytes, type);
        } catch (RuntimeException | IOException ex) { count(region, "decode_failure"); return null; }
    }

    private byte[] encode(byte[] bytes) throws IOException {
        if (bytes.length < 32768) return bytes;
        var output = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(output)) { gzip.write(bytes); }
        byte[] compressed = output.toByteArray();
        return compressed.length < bytes.length ? compressed : bytes;
    }

    private String digest(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private void unavailable(String region) {
        unavailableUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        count(region, "redis_unavailable");
    }

    private void count(String region, String outcome) {
        if (metrics != null) metrics.counter("rigour.query.cache.requests", "region", region, "outcome", outcome).increment();
    }

    public static final class QueryBusyException extends RuntimeException {
        public QueryBusyException() { super("查询繁忙，请稍后重试"); }
    }
}
