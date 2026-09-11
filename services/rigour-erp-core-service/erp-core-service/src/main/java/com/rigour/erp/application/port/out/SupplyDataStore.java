package com.rigour.erp.application.port.out;

import com.rigour.erp.application.model.DictionaryMappingAudit;
import com.rigour.erp.application.model.DictionaryMappingAudit.MappingIssue;
import com.rigour.erp.domain.model.supply.InventoryBalance;
import com.rigour.erp.domain.model.supply.PurchaseOrder;
import com.rigour.erp.domain.model.supply.PurchaseReturn;
import com.rigour.erp.domain.model.supply.Supplier;
import com.rigour.erp.domain.model.supply.SupplyDataObjectType;
import com.rigour.erp.domain.model.supply.Warehouse;
import com.rigour.erp.domain.model.supply.WarehousingReceipt;
import com.rigour.integration.api.v1.model.DhbApiModels.ExternalObjectMappingCommand;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** ERP 供应链同步持久化端口；订货宝来源只能映射到 ERP 自研业务表。 */
public interface SupplyDataStore {
    /**
     * 订货宝库存接口按来源商品编码批量查询，这里返回来源编码，不返回 ERP 自研商品编码。
     */
    List<String> sourceProductCodes(String tenantId, UUID connectorId);

    UUID startRun(String tenantId, UUID connectorId, UUID actorId, SupplyDataObjectType type, int maxPages);

    /** 定时同步使用同一批次与互斥锁模型，但在批次中记录 SCHEDULED 触发方式。 */
    default UUID startScheduledRun(String tenantId, UUID connectorId, UUID actorId,
                                   SupplyDataObjectType type, int maxPages) {
        return startRun(tenantId, connectorId, actorId, type, maxPages);
    }

    ImportResult importSupplier(String tenantId, UUID runId, Supplier item);

    ImportResult importPurchaseOrder(String tenantId, UUID runId, PurchaseOrder item);

    ImportResult importPurchaseReturn(String tenantId, UUID runId, PurchaseReturn item);

    ImportResult importWarehousingReceipt(String tenantId, UUID runId, WarehousingReceipt item);

    ImportResult importWarehouse(String tenantId, UUID runId, Warehouse item);

    ImportResult importInventory(String tenantId, UUID runId, InventoryBalance item);

    /** 库存快照通常是商品 x 仓库矩阵，批量导入可复用本批次映射解析结果。 */
    default ImportResult importInventories(String tenantId, UUID runId, List<InventoryBalance> items) {
        if (items == null || items.isEmpty()) {
            return new ImportResult(0, 0, 0, 0, List.of());
        }
        long created = 0;
        long changed = 0;
        long duplicates = 0;
        long rejected = 0;
        List<MappingIssue> issues = new java.util.ArrayList<>();
        for (InventoryBalance item : items) {
            ImportResult result = importInventory(tenantId, runId, item);
            created += result.created();
            changed += result.changed();
            duplicates += result.duplicates();
            rejected += result.rejected();
            issues.addAll(result.issues());
        }
        return new ImportResult(created, changed, duplicates, rejected, issues);
    }

    /** 仅在完整且无拒绝记录的全量快照后标记来源存在/缺失，不删除业务记录。 */
    void reconcileSourcePresence(String tenantId, UUID runId, Map<String, Set<String>> seenSourceIds);

    /** 在同一本地事务内完成来源存在性核对与成功 run 终态。 */
    void completeRunWithSourcePresence(String tenantId, UUID runId,
                                       Map<String, Set<String>> seenSourceIds,
                                       RunStatistics statistics);

    /** 将 ERP 已解析的供应链来源绑定发布给 Integration，供 Order/调拨投影稳定引用。 */
    default List<ExternalObjectMappingCommand> externalObjectMappings(
            String tenantId, UUID connectorId, UUID runId, SupplyDataObjectType objectType) {
        return List.of();
    }

    void completeRun(String tenantId, UUID runId, RunStatistics statistics);

    void failRun(String tenantId, UUID runId, RunStatistics statistics, RuntimeException error);

    /** 长批次导入过程中续租本地互斥锁，避免短锁误释放正在运行的同步。 */
    default void heartbeatRun(String tenantId, UUID runId) { }

    record ImportResult(long created, long changed, long duplicates, long rejected,
                        List<MappingIssue> issues) {
        public ImportResult {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
        public static ImportResult oneCreated() { return new ImportResult(1, 0, 0, 0, List.of()); }
        public static ImportResult oneChanged() { return new ImportResult(0, 1, 0, 0, List.of()); }
        public static ImportResult oneDuplicate() { return new ImportResult(0, 0, 1, 0, List.of()); }
        public static ImportResult oneRejected() { return new ImportResult(0, 0, 0, 1, List.of()); }
        public static ImportResult rejectedIssue(String fieldCode, String sourceValue) {
            return new ImportResult(0, 0, 0, 1,
                    List.of(new MappingIssue("ERP_REFERENCE", fieldCode, sourceValue, 1)));
        }
    }

    /** 本批次交给 ERP 导入流程并完成统计的记录总数。 */
    record RunStatistics(long fetched, long created, long changed, long duplicates,
                         long rejected, int pages, DictionaryMappingAudit dictionaryAudit) {
        public RunStatistics {
            dictionaryAudit = dictionaryAudit == null ? DictionaryMappingAudit.empty() : dictionaryAudit;
        }
    }
}
