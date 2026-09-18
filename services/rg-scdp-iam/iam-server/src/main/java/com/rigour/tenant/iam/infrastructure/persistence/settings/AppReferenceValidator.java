package com.rigour.tenant.iam.infrastructure.persistence.settings;

import com.rigour.tenant.iam.application.port.out.AppReferenceClient;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/** 每个 IAM 事务每个维度只读取一次权威目录；事务结束即丢弃，批量提交不复用预览缓存。 */
@Component
public final class AppReferenceValidator {
    private final AppReferenceClient client;

    public AppReferenceValidator(AppReferenceClient client) {
        this.client = client;
    }

    public void requireActive(UUID tenant, String dimension, Collection<String> keys) {
        if (keys.isEmpty()) return;
        Map<String, AppReferenceClient.Reference> values = new HashMap<>();
        for (var ref : references(tenant, dimension)) values.put(ref.key(), ref);
        for (String key : keys)
            if (!values.containsKey(key) || !"ACTIVE".equals(values.get(key).status()))
                throw new IllegalArgumentException("请选择本租户有效的授权范围：" + dimension + " / " + key);
    }

    @SuppressWarnings("unchecked")
    private List<AppReferenceClient.Reference> references(UUID tenant, String dimension) {
        if (!TransactionSynchronizationManager.isSynchronizationActive())
            return client.references(tenant, dimension);
        Map<String, List<AppReferenceClient.Reference>> reads =
                (Map<String, List<AppReferenceClient.Reference>>)
                        TransactionSynchronizationManager.getResource(this);
        if (reads == null) {
            reads = new HashMap<>();
            TransactionSynchronizationManager.bindResource(this, reads);
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            TransactionSynchronizationManager.unbindResourceIfPossible(
                                    AppReferenceValidator.this);
                        }
                    });
        }
        return reads.computeIfAbsent(
                tenant + ":" + dimension, key -> client.references(tenant, dimension));
    }
}
