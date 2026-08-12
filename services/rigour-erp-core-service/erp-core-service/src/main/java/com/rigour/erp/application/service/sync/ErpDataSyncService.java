package com.rigour.erp.application.service.sync;

import com.rigour.erp.api.v1.model.ErpDataSyncCommand;
import com.rigour.erp.api.v1.model.ErpDataSyncResult;
import com.rigour.erp.application.service.product.ProductMasterDataSyncService;
import com.rigour.erp.application.service.supply.SupplyDataSyncService;
import com.rigour.erp.domain.model.product.MasterDataObjectType;
import com.rigour.erp.domain.model.supply.SupplyDataObjectType;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.util.List;
import java.util.Locale;
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

    public ErpDataSyncService(ProductMasterDataSyncService productSync,
                              SupplyDataSyncService supplySync) {
        this.productSync = productSync;
        this.supplySync = supplySync;
    }

    public ErpDataSyncResult run(ErpDataSyncCommand command) {
        ParsedCommand parsed = parse(command);
        String value = parsed.objectType();
        log.info("ERP统一数据同步请求开始 objectType={} maxPages={} domain={}",
                value, parsed.maxPages(), parsed.productType() != null ? "PRODUCT" : "SUPPLY");
        try {
            ErpDataSyncResult result = parsed.productType() != null
                    ? productSync.run(parsed.productType(), parsed.maxPages())
                    : supplySync.run(parsed.supplyType(), parsed.maxPages());
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
    public ErpDataSyncResult runScheduled(CallerIdentity caller, UUID connectorId,
                                          ErpDataSyncCommand command) {
        requireScheduledCaller(caller);
        if (connectorId == null) throw new IllegalArgumentException("connectorId不能为空");
        ParsedCommand parsed = parse(command);
        log.info("ERP统一定时数据同步开始 tenantId={} objectType={} connectorId={} maxPages={}",
                caller.tenantId(), parsed.objectType(), connectorId, parsed.maxPages());
        return parsed.productType() != null
                ? productSync.runScheduled(caller, connectorId, parsed.productType(), parsed.maxPages())
                : supplySync.runScheduled(caller, connectorId, parsed.supplyType(), parsed.maxPages());
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
        return new ParsedCommand(value, command.effectiveMaxPages(), productType, supplyType);
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

    private record ParsedCommand(String objectType, int maxPages,
                                 MasterDataObjectType productType,
                                 SupplyDataObjectType supplyType) { }

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
