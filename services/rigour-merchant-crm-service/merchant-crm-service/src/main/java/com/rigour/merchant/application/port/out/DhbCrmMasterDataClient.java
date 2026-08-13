package com.rigour.merchant.application.port.out;

import com.rigour.merchant.domain.model.CrmMasterDataObjectType;
import com.rigour.shared.context.CallerIdentity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** CRM 到 Integration 的出站端口；实现不得直接访问订货宝。 */
public interface DhbCrmMasterDataClient {
    Collected collect(CallerIdentity serviceCaller, UUID connectorId,
                      CrmMasterDataObjectType objectType, int maxPages);

    record Collected(CrmMasterDataObjectType objectType, long total, int pages,
                     List<SourceRecord> items) {
        public Collected {
            if (objectType == null) throw new IllegalArgumentException("objectType不能为空");
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    record SourceRecord(String sourceId, String sourceCode, String sourceName,
                        String sourceStatus, Instant sourceCreatedAt,
                        Instant sourceUpdatedAt, Map<String, Object> sourceFields) {
        public SourceRecord {
            if (sourceId == null || sourceId.isBlank()) {
                throw new IllegalArgumentException("订货宝响应缺少来源业务键");
            }
            sourceFields = sourceFields == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(sourceFields));
        }
    }
}
