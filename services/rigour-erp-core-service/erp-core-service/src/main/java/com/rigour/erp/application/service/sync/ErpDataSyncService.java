package com.rigour.erp.application.service.sync;

import com.rigour.erp.api.v1.model.ErpDataSyncCommand;
import com.rigour.erp.api.v1.model.ErpDataSyncResult;
import com.rigour.erp.application.port.out.ErpSyncRunAuditStore;
import com.rigour.erp.application.service.product.ProductMasterDataSyncService;
import com.rigour.erp.application.service.supply.SupplyDataSyncService;
import com.rigour.erp.domain.model.product.MasterDataObjectType;
import com.rigour.erp.domain.model.supply.SupplyDataObjectType;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 统一解析 objectType，再把同步交给对应业务子域。 */
@Service
public final class ErpDataSyncService {
    private static final Logger log = LoggerFactory.getLogger(ErpDataSyncService.class);
    private static final String SUPPORTED_TYPES = "PRODUCT_SPU、CATEGORY、BRAND、SPECIFICATION、TAG、"
            + "SUPPLIER、PURCHASE_ORDER、PURCHASE_RETURN、WAREHOUSING_RECEIPT、WAREHOUSE、INVENTORY";

    private final ProductMasterDataSyncService productSync;
    private final SupplyDataSyncService supplySync;
    private final ErpSyncRunAuditStore syncRunAuditStore;

    public ErpDataSyncService(ProductMasterDataSyncService productSync,
                              SupplyDataSyncService supplySync,
                              ErpSyncRunAuditStore syncRunAuditStore) {
        this.productSync = productSync;
        this.supplySync = supplySync;
        this.syncRunAuditStore = syncRunAuditStore;
    }

    public ErpDataSyncResult run(ErpDataSyncCommand command) {
        ParsedCommand parsed = parse(command);
        String value = parsed.objectType();
        log.info("ERP统一数据同步请求开始 objectType={} maxPages={} domain={}",
                value, parsed.maxPages(), parsed.productType() != null ? "PRODUCT" : "SUPPLY");
        try {
            ErpDataSyncResult result = parsed.productType() != null
                    ? runProduct(parsed)
                    : runSupply(parsed);
            log.info("ERP统一数据同步请求完成 objectType={} runId={} connectorId={} fetched={} created={} changed={} duplicates={} rejected={} pages={}",
                    value, result.runId(), result.connectorId(), result.fetched(), result.created(),
                    result.changed(), result.duplicates(), result.rejected(), result.pages());
            return result;
        } catch (RuntimeException error) {
            log.warn("ERP统一数据同步请求失败 objectType={} maxPages={} errorType={} reason={}",
                    value, parsed.maxPages(), error.getClass().getSimpleName(), oneLine(error.getMessage()));
            throw error;
        }
    }

    /** 供 ERP 内部定时调度器调用；不依赖 HTTP 线程上下文，也不暴露为新的浏览器接口。 */
    public ErpDataSyncResult runScheduled(CallerIdentity caller, UUID connectorId, UUID sourceTaskId,
                                          ErpDataSyncCommand command) {
        return runInternal(caller, connectorId, sourceTaskId, "SCHEDULED", command);
    }

    /** 供 Integration 编排器调用；按上游触发方式记录 ERP 本地同步批次。 */
    public ErpDataSyncResult runInternal(CallerIdentity caller, UUID connectorId, UUID sourceTaskId,
                                         String triggerType, ErpDataSyncCommand command) {
        requireScheduledCaller(caller);
        if (connectorId == null) throw new IllegalArgumentException("connectorId不能为空");
        if (sourceTaskId == null) throw new IllegalArgumentException("sourceTaskId不能为空");
        ParsedCommand parsed = parse(command);
        String normalizedTriggerType = triggerType(triggerType);
        boolean scheduled = "SCHEDULED".equals(normalizedTriggerType);
        log.info("ERP统一内部数据同步开始 tenantId={} triggerType={} objectType={} connectorId={} maxPages={}",
                caller.tenantId(), normalizedTriggerType, parsed.objectType(), connectorId, parsed.maxPages());
        try {
            return parsed.productType() != null
                    ? runInternalProduct(caller, connectorId, parsed, scheduled)
                    : runInternalSupply(caller, connectorId, parsed, scheduled);
        } catch (ErpScheduledSyncSkipException skip) {
            if (!scheduled) throw skip;
            UUID runId = syncRunAuditStore.recordScheduledSkip(caller.tenantId(), connectorId, sourceTaskId,
                    skip.blockedObjectType(), parsed.maxPages(), skip.reason());
            log.info("ERP统一定时数据同步跳过 tenantId={} objectType={} connectorId={} sourceTaskId={} runId={} reason={}",
                    caller.tenantId(), skip.blockedObjectType(), connectorId, sourceTaskId, runId,
                    skip.reason().code());
            return new ErpDataSyncResult(runId, skip.blockedObjectType(), "SKIPPED", connectorId,
                    0, 0, 0, 0, 0, 0, Map.of(), Map.of(), 0, Instant.now());
        }
    }

    private ErpDataSyncResult runProduct(ParsedCommand parsed) {
        return parsed.hasWindow()
                ? productSync.run(parsed.productType(), parsed.maxPages(), parsed.from(), parsed.to())
                : productSync.run(parsed.productType(), parsed.maxPages());
    }

    private ErpDataSyncResult runSupply(ParsedCommand parsed) {
        return parsed.hasWindow()
                ? supplySync.run(parsed.supplyType(), parsed.maxPages(), parsed.from(), parsed.to())
                : supplySync.run(parsed.supplyType(), parsed.maxPages());
    }

    private ErpDataSyncResult runInternalProduct(CallerIdentity caller, UUID connectorId,
                                                 ParsedCommand parsed, boolean scheduled) {
        if (scheduled) {
            return parsed.hasWindow()
                    ? productSync.runScheduled(caller, connectorId, parsed.productType(),
                    parsed.maxPages(), parsed.from(), parsed.to())
                    : productSync.runScheduled(caller, connectorId, parsed.productType(), parsed.maxPages());
        }
        return parsed.hasWindow()
                ? productSync.runInternal(caller, connectorId, parsed.productType(),
                parsed.maxPages(), scheduled, parsed.from(), parsed.to())
                : productSync.runInternal(caller, connectorId, parsed.productType(), parsed.maxPages(), scheduled);
    }

    private ErpDataSyncResult runInternalSupply(CallerIdentity caller, UUID connectorId,
                                                ParsedCommand parsed, boolean scheduled) {
        if (scheduled) {
            return parsed.hasWindow()
                    ? supplySync.runScheduled(caller, connectorId, parsed.supplyType(),
                    parsed.maxPages(), parsed.from(), parsed.to())
                    : supplySync.runScheduled(caller, connectorId, parsed.supplyType(), parsed.maxPages());
        }
        return parsed.hasWindow()
                ? supplySync.runInternal(caller, connectorId, parsed.supplyType(),
                parsed.maxPages(), scheduled, parsed.from(), parsed.to())
                : supplySync.runInternal(caller, connectorId, parsed.supplyType(), parsed.maxPages(), scheduled);
    }

    private static ParsedCommand parse(ErpDataSyncCommand command) {
        if (command == null || command.objectType() == null || command.objectType().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "objectType不能为空", List.of());
        }
        String value = command.objectType().strip().toUpperCase(Locale.ROOT);
        MasterDataObjectType productType = enumValue(MasterDataObjectType.class, value);
        SupplyDataObjectType supplyType = enumValue(SupplyDataObjectType.class, value);
        if (productType == null && supplyType == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "objectType只支持" + SUPPORTED_TYPES, List.of());
        }
        return new ParsedCommand(value, command.effectiveMaxPages(), productType, supplyType,
                command.from(), command.to());
    }

    private static void requireScheduledCaller(CallerIdentity caller) {
        if (caller == null || caller.tenantId() == null || caller.userId() != null
                || !"SERVICE".equals(caller.principalScope())) {
            throw new AuthorizationDeniedException("tenant-service-caller");
        }
        if (!caller.permissions().contains("integration:dhb:read")
                && !caller.permissions().contains("*:*:*")) {
            throw new AuthorizationDeniedException("integration:dhb:read");
        }
    }

    private static String triggerType(String value) {
        String normalized = value == null || value.isBlank()
                ? "SCHEDULED"
                : value.strip().toUpperCase(Locale.ROOT);
        if (!"SCHEDULED".equals(normalized) && !"MANUAL".equals(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "triggerType只支持MANUAL或SCHEDULED", List.of());
        }
        return normalized;
    }

    private record ParsedCommand(String objectType, int maxPages,
                                 MasterDataObjectType productType,
                                 SupplyDataObjectType supplyType,
                                 Instant from, Instant to) {
        private boolean hasWindow() {
            return from != null;
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String oneLine(String value) {
        return value == null ? "-" : value.replace('\r', ' ').replace('\n', ' ');
    }
}
