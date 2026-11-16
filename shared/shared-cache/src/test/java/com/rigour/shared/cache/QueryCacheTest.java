package com.rigour.shared.cache;

import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class QueryCacheTest {
    record LargeValue(String text) {}
    @Test void largeResponsesAreCompressedAndLegacyJsonStillReadsWithoutReloading() {
        var store = new Memory();
        var c = new QueryCache(store, new Versions(), () -> "scope", () -> false,
                null, "test", 2097152, 2);
        var value = new LargeValue("门店地图聚合结果".repeat(10000));
        var type = new TypeReference<LargeValue>() {};
        var loads = new AtomicInteger();
        Supplier<LargeValue> loader = () -> { loads.incrementAndGet(); return value; };
        assertEquals(value, c.read("a", "map", 1, 30, type, loader));
        byte[] stored = store.values.values().iterator().next();
        assertEquals((byte) 0x1f, stored[0]);
        assertEquals((byte) 0x8b, stored[1]);
        assertTrue(stored.length < 10000);
        assertEquals(value, c.read("a", "map", 1, 30, type, loader));
        assertEquals(1, loads.get());
        byte[] legacy = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(value);
        store.values.replaceAll((key, bytes) -> legacy);
        assertEquals(value, c.read("a", "map", 1, 30, type, loader));
        assertEquals(1, loads.get());
    }

    @Test void compressedEntriesRespectTheUncompressedSizeLimit() throws Exception {
        var store = new Memory();
        var c = cache(store, new Versions(), () -> "scope");
        c.read("a", "page", 1, 30, TYPE, () -> new Value(1, Instant.EPOCH));
        var out = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.GZIPOutputStream(out)) { zip.write(new byte[100000]); }
        store.values.replaceAll((key, bytes) -> out.toByteArray());
        assertEquals(2, c.read("a", "page", 1, 30, TYPE, () -> new Value(2, Instant.EPOCH)).n());
    }
    record Value(int n, Instant generatedAt) {}
    static final TypeReference<Value> TYPE = new TypeReference<>() {};
    static class Memory implements CacheStore {
        final Map<String, byte[]> values = new ConcurrentHashMap<>();
        volatile boolean failed;
        public Optional<byte[]> get(String tenant,String key) { if(failed)throw new IllegalStateException();return Optional.ofNullable(values.get(tenant+key)); }
        public void put(String tenant,String key,byte[] v,Duration ttl) { if(failed)throw new IllegalStateException(); assertTrue(ttl.toMillis()<=30000);values.put(tenant+key,v); }
        public void evict(String tenant,String key){values.remove(tenant+key);}
        public boolean tryLock(String t,String k,String token,Duration ttl){if(failed)throw new IllegalStateException();return true;}
        public void unlock(String t,String k,String token){}
    }
    static class Versions implements CacheVersions {
        final AtomicLong value = new AtomicLong();
        public long current(String tenant){return value.get();}
        public void changed(String tenant){value.incrementAndGet();}
        public boolean dirty(String tenant){return false;}
    }
    QueryCache cache(Memory store,Versions v,Supplier<Object> scope){return new QueryCache(store,v,scope,()->false,null,"test",4096,2);}
    @Test void tenantAuthorityFilterAndPageAreIsolatedAndMoneyPayloadIsTyped() {
        var store=new Memory();var versions=new Versions();var scope=new AtomicReference<Object>("user-1:v1");
        var c=cache(store,versions,scope::get);var calls=new AtomicInteger();
        Supplier<Value> load=()->new Value(calls.incrementAndGet(),Instant.parse("2026-11-10T00:00:00Z"));
        assertEquals(c.read("a","page",List.of("filter",1),30,TYPE,load),c.read("a","page",List.of("filter",1),30,TYPE,load));
        assertEquals(1,calls.get());
        c.read("b","page",List.of("filter",1),30,TYPE,load);
        c.read("a","page",List.of("filter",2),30,TYPE,load);
        scope.set("user-1:v2");c.read("a","page",List.of("filter",1),30,TYPE,load);
        scope.set("user-2:v1");c.read("a","page",List.of("filter",1),30,TYPE,load);
        assertEquals(5,calls.get());
    }
    @Test void committedVersionMakesEveryOldPageUnreachableAndLateFillCannotPoisonNewVersion() {
        var store=new Memory();var versions=new Versions();var c=cache(store,versions,()->"scope");
        c.read("a","page",1,30,TYPE,()->{versions.changed("a");return new Value(1,Instant.EPOCH);});
        assertTrue(store.values.isEmpty());
        assertEquals(2,c.read("a","page",1,30,TYPE,()->new Value(2,Instant.EPOCH)).n());
        versions.changed("a");
        assertEquals(3,c.read("a","page",1,30,TYPE,()->new Value(3,Instant.EPOCH)).n());
    }
    @Test void redisFailureDegradesWithoutCachingBusinessErrors() {
        var store=new Memory();store.failed=true;var c=cache(store,new Versions(),()->"scope");
        assertEquals(7,c.read("a","page",1,30,TYPE,()->new Value(7,Instant.EPOCH)).n());
        assertThrows(IllegalArgumentException.class,()->c.read("a","page",2,30,TYPE,()->{throw new IllegalArgumentException("invalid");}));
        assertEquals(8,c.read("a","page",2,30,TYPE,()->new Value(8,Instant.EPOCH)).n());
    }
    @Test void concurrentMissesShareOneLoadAndNestedStatisticsDoNotDeadlock() throws Exception {
        var c=cache(new Memory(),new Versions(),()->"scope");var calls=new AtomicInteger();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            Supplier<Value> load=()->{calls.incrementAndGet();entered.countDown();try{release.await();}catch(InterruptedException ex){throw new IllegalStateException(ex);}return c.read("a","totals",1,30,TYPE,()->new Value(5,Instant.EPOCH));};
            var first=executor.submit(()->c.read("a","page",1,30,TYPE,load));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            var second=executor.submit(()->c.read("a","page",1,30,TYPE,load));
            release.countDown();assertEquals(first.get(2,TimeUnit.SECONDS),second.get(2,TimeUnit.SECONDS));assertEquals(1,calls.get());
        }
    }
    @Test void bypassRebuildsAndUnauthenticatedReadsNeverPopulateCache() {
        var store=new Memory();var versions=new Versions();var c=new QueryCache(store,versions,()->"scope",()->true,null,"test",4096,2);
        var calls=new AtomicInteger();Supplier<Value> load=()->new Value(calls.incrementAndGet(),Instant.EPOCH);
        c.read("a","page",1,30,TYPE,load);c.read("a","page",1,30,TYPE,load);assertEquals(2,calls.get());
        store.values.clear();var anonymous=cache(store,versions,()->null);
        anonymous.read("a","page",1,30,TYPE,load);assertTrue(store.values.isEmpty());
    }
}
