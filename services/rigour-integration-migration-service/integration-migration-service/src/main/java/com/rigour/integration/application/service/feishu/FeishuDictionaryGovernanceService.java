package com.rigour.integration.application.service.feishu;

import com.rigour.integration.api.v1.model.DictionaryRescanResult;
import com.rigour.integration.application.port.out.FeishuImportStore;
import com.rigour.integration.application.service.DictionarySourceMappingService;
import com.rigour.settings.client.BusinessDictionaryBatchClient;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Observation;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 复用导入的白名单提取器，只回填来源映射，不改动业务事实或原始报文。 */
@Service
public class FeishuDictionaryGovernanceService {
    private static final int BATCH_LIMIT = 100;
    private static final int ROW_LIMIT = 100_000;
    private final FeishuImportStore rawStore;
    private final DictionarySourceMappingService mappings;

    public FeishuDictionaryGovernanceService(FeishuImportStore rawStore, DictionarySourceMappingService mappings) {
        this.rawStore = rawStore;
        this.mappings = mappings;
    }

    public DictionaryRescanResult rescan(String dictionaryCode) {
        AuthorizationContext.requirePermission("business-settings:dict:write");
        var tenant = AuthorizationContext.requireCurrent().tenantId();
        if (tenant == null || dictionaryCode == null || !dictionaryCode.matches("[A-Z][A-Z0-9_]{0,49}")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "需要租户和有效字典编码", List.of());
        }
        var batches = rawStore.recentBatches(tenant, BATCH_LIMIT);
        // 仓储会截断到上限；达到上限时保守提示可能还有未扫描的历史记录。
        boolean truncated = batches.size() >= BATCH_LIMIT;
        int rows = 0;
        Set<Observation> observed = new LinkedHashSet<>();
        var mapper = new FeishuDictionaryObservationMapper();
        for (var batch : batches) {
            var raw = rawStore.rawRowsForBatch(tenant, batch.id(), ROW_LIMIT);
            truncated |= raw.size() >= ROW_LIMIT;
            rows += raw.size();
            mapper.observations(raw).stream()
                    .filter(observation -> observation.dictionaryCode().equals(dictionaryCode))
                    .forEach(observed::add);
        }
        var audit = mappings.syncObserved(BusinessDictionaryBatchClient.serviceCaller(
                "rigour-integration-feishu-import-service", "FEISHU_IMPORT_SERVICE", tenant),
                "FEISHU_IMPORT", observed);
        return new DictionaryRescanResult(batches.size(), rows, observed.size(), audit.unmapped(), truncated);
    }
}
