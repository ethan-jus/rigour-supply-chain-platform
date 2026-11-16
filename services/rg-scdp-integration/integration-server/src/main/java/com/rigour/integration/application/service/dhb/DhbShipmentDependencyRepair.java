package com.rigour.integration.application.service.dhb;

import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.integration.application.port.out.CrmDhbDomainSyncClient;
import com.rigour.integration.application.port.out.DhbIntegrationStore;
import com.rigour.integration.application.port.out.ErpDhbDomainSyncClient;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 发货依赖补齐仅刷新主数据，不拉采购、付款或库存；同批次同类资料最多刷新一次。 */
@Component
public class DhbShipmentDependencyRepair {
    private final DhbIntegrationStore store;
    private final ErpDhbDomainSyncClient erp;
    private final CrmDhbDomainSyncClient crm;

    public DhbShipmentDependencyRepair(
            DhbIntegrationStore store, ErpDhbDomainSyncClient erp, CrmDhbDomainSyncClient crm) {
        this.store = store;
        this.erp = erp;
        this.crm = crm;
    }

    public Session session(UUID tenantId, UUID connectorId, int maxPages) {
        return new Session(tenantId, connectorId, maxPages);
    }

    public final class Session {
        private final UUID tenantId;
        private final UUID connectorId;
        private final int maxPages;
        private final Map<String, Result> results = new ConcurrentHashMap<>();

        private Session(UUID tenantId, UUID connectorId, int maxPages) {
            this.tenantId = tenantId;
            this.connectorId = connectorId;
            this.maxPages = maxPages;
        }

        public Result refresh(List<String> objectTypes) {
            String type =
                    objectTypes.contains("CUSTOMER")
                            ? "CUSTOMER"
                            : objectTypes.contains("WAREHOUSE")
                                    ? "WAREHOUSE"
                                    : objectTypes.stream()
                                                    .anyMatch(
                                                            t ->
                                                                    t.startsWith("PRODUCT")
                                                                            || t.equals("SKU"))
                                            ? "PRODUCT_SPU"
                                            : null;
            if (type == null) return new Result(false, "该资料需人工核对");
            // computeIfAbsent 也缓存失败/结果不明，避免多线程按每张发货单重复请求远端写入。
            return results.computeIfAbsent(type, this::sync);
        }

        private Result sync(String type) {
            try {
                var caller = DhbSyncOrchestrationService.serviceCaller(tenantId);
                if ("CUSTOMER".equals(type)) {
                    var target = target(store.configuredCrmMasterSyncTargets());
                    var result =
                            crm.syncObject(
                                    caller,
                                    connectorId,
                                    target.taskId(),
                                    "CUSTOMER",
                                    maxPages,
                                    null,
                                    null);
                    if (result == null
                            || !List.of("SUCCEEDED", "SUCCEEDED_WITH_WARNINGS")
                                    .contains(result.status()))
                        return new Result(true, "客户补同步未完成，请在客户档案补录资料，再关联本发货单");
                    return new Result(true, "已补同步客户，仍缺失的来源关联请到客户档案核对");
                }
                var target =
                        target(
                                "WAREHOUSE".equals(type)
                                        ? store.configuredSupplyChainSyncTargets()
                                        : store.configuredProductMasterSyncTargets());
                var types =
                        "WAREHOUSE".equals(type)
                                ? List.of("WAREHOUSE")
                                : List.of(
                                        "CATEGORY", "BRAND", "SPECIFICATION", "TAG", "PRODUCT_SPU");
                for (String kind : types) {
                    var result =
                            erp.sync(
                                    caller,
                                    connectorId,
                                    target.taskId(),
                                    kind,
                                    maxPages,
                                    "SCHEDULED",
                                    null,
                                    null);
                    if (result == null
                            || !List.of("SUCCEEDED", "SUCCEEDED_WITH_WARNINGS")
                                    .contains(result.status()))
                        return new Result(true, "资料补同步未完成，请在对应档案补录资料，再关联本发货单");
                }
                return new Result(true, "已补同步资料，仍缺失的来源关联请到对应档案核对");
            } catch (RuntimeException error) {
                // 不把外部响应/密钥或未知异常原文暴露给业务，也不在结果不明时再次写入。
                return new Result(true, "资料补同步未取得完整结果，请先核对已有档案；仍缺失的请补录，再关联本发货单");
            }
        }

        private SyncTargetView target(List<SyncTargetView> targets) {
            var matches =
                    targets.stream()
                            .filter(
                                    t ->
                                            tenantId.equals(t.tenantId())
                                                    && connectorId.equals(t.connectorId()))
                            .distinct()
                            .toList();
            if (matches.size() != 1) throw new IllegalStateException("未配置唯一的同来源同步任务");
            return matches.getFirst();
        }
    }

    public record Result(boolean attempted, String message) {}
}
