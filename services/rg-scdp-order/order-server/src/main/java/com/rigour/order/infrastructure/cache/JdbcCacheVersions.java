package com.rigour.order.infrastructure.cache;

import com.rigour.shared.cache.CacheVersions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The revision row is updated once, immediately before commit, including scheduled/import writes. */
public final class JdbcCacheVersions implements CacheVersions {
    private final JdbcTemplate jdbc;
    public JdbcCacheVersions(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private String resource(String tenant) { return "order:query-cache:" + tenant; }
    public long current(String tenant) {
        String key=resource(tenant)+":read";
        Object cached=TransactionSynchronizationManager.getResource(key);
        if(cached instanceof Long revision) return revision;
        var rows = jdbc.queryForList("SELECT revision FROM query_cache_version WHERE tenant_id=?", Long.class, tenant);
        long revision=rows.isEmpty() ? 0 : rows.getFirst();
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.bindResource(key,revision);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void suspend(){TransactionSynchronizationManager.unbindResourceIfPossible(key);}
                @Override public void resume(){TransactionSynchronizationManager.bindResource(key,revision);}
                @Override public void afterCompletion(int status){TransactionSynchronizationManager.unbindResourceIfPossible(key);}
            });
        }
        return revision;
    }
    public boolean dirty(String tenant) {
        return TransactionSynchronizationManager.hasResource(resource(tenant));
    }
    public void changed(String tenant) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Cache version must share the business transaction");
        String key = resource(tenant);
        if (dirty(tenant)) return;
        TransactionSynchronizationManager.bindResource(key, Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                jdbc.update("INSERT INTO query_cache_version(tenant_id,revision) VALUES(?,1) "
                        + "ON DUPLICATE KEY UPDATE revision=revision+1", tenant);
            }
            @Override public void suspend() { TransactionSynchronizationManager.unbindResourceIfPossible(key); }
            @Override public void resume() { TransactionSynchronizationManager.bindResource(key, Boolean.TRUE); }
            @Override public void afterCompletion(int status) { TransactionSynchronizationManager.unbindResourceIfPossible(key); }
        });
    }
}
