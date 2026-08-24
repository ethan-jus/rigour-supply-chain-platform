package com.rigour.erp.application.service.inventory;

import com.rigour.erp.api.v1.model.InternalInventoryWarehouseCommand;
import com.rigour.erp.api.v1.model.InternalInventoryWarehouseView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpInventoryWarehouseStore;
import com.rigour.erp.application.port.out.ErpInventoryWarehouseStore.WarehouseSearchCriteria;
import com.rigour.erp.application.service.support.ErpServiceValidation;
import com.rigour.erp.domain.code.ErpBusinessCodeRules;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** ERP 仓库维护用例；仓库删除只做逻辑删除，停用通过 statusCode 表达。 */
@Service
public final class ErpInventoryWarehouseService {
    private static final Logger log = LoggerFactory.getLogger(ErpInventoryWarehouseService.class);
    private static final String READ_PERMISSION = "erp:product:read";
    private static final String WRITE_PERMISSION = "erp:product:write";

    private final ErpInventoryWarehouseStore store;
    private final BusinessCodeGenerator codeGenerator;

    @Autowired
    public ErpInventoryWarehouseService(ErpInventoryWarehouseStore store) {
        this(store, new BusinessCodeGenerator());
    }

    ErpInventoryWarehouseService(ErpInventoryWarehouseStore store, BusinessCodeGenerator codeGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public MasterDataPageView<InternalInventoryWarehouseView> warehouses(
            int begin, int step, String warehouseCode, String warehouseName, String regionCode,
            Boolean defaultFlag, String statusCode) {
        String tenantId = tenant(READ_PERMISSION);
        WarehouseSearchCriteria criteria = new WarehouseSearchCriteria(
                ErpServiceValidation.text(warehouseCode, 50, "warehouseCode"),
                ErpServiceValidation.text(warehouseName, 120, "warehouseName"),
                ErpServiceValidation.code(regionCode, "regionCode", false),
                defaultFlag,
                ErpServiceValidation.code(statusCode, "statusCode", false));
        MasterDataPageView<InternalInventoryWarehouseView> result = store.warehouses(
                tenantId, ErpServiceValidation.pageBegin(begin), ErpServiceValidation.pageStep(step), criteria);
        log.debug("ERP仓库列表查询完成 tenantId={} warehouseCode={} warehouseName={} regionCode={} defaultFlag={} statusCode={} count={} total={}",
                tenantId, ErpServiceValidation.value(criteria.warehouseCode()),
                ErpServiceValidation.value(criteria.warehouseName()),
                ErpServiceValidation.value(criteria.regionCode()), criteria.defaultFlag(),
                ErpServiceValidation.value(criteria.statusCode()), result.items().size(), result.total());
        return result;
    }

    public InternalInventoryWarehouseView warehouse(Long id) {
        String tenantId = tenant(READ_PERMISSION);
        InternalInventoryWarehouseView result = store.warehouse(tenantId,
                        ErpServiceValidation.requireId(id, "仓库ID无效"))
                .orElseThrow(() -> notFound("仓库不存在"));
        log.debug("ERP仓库详情查询完成 tenantId={} warehouseId={} warehouseCode={}",
                tenantId, result.id(), result.warehouseCode());
        return result;
    }

    public InternalInventoryWarehouseView create(InternalInventoryWarehouseCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        InternalInventoryWarehouseCommand normalized = normalize(command, false);
        String tenantId = actor.tenantId().toString();
        String warehouseCode = codeGenerator.generateUnique(ErpBusinessCodeRules.WAREHOUSE,
                candidate -> !store.existsByCode(tenantId, candidate));
        InternalInventoryWarehouseView created = store.create(
                tenantId, warehouseCode, normalized, actor.principalId().toString());
        log.info("ERP仓库创建完成 tenantId={} warehouseId={} warehouseCode={} warehouseName={} actorId={}",
                tenantId, created.id(), created.warehouseCode(), created.warehouseName(), actor.principalId());
        return created;
    }

    public InternalInventoryWarehouseView update(Long id, InternalInventoryWarehouseCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        InternalInventoryWarehouseCommand normalized = normalize(command, true);
        String tenantId = actor.tenantId().toString();
        InternalInventoryWarehouseView updated = store.update(tenantId,
                ErpServiceValidation.requireId(id, "仓库ID无效"), normalized, actor.principalId().toString());
        log.info("ERP仓库修改完成 tenantId={} warehouseId={} warehouseCode={} revision={} actorId={}",
                tenantId, updated.id(), updated.warehouseCode(), updated.revision(), actor.principalId());
        return updated;
    }

    public void delete(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        ErpServiceValidation.requireRevision(revision);
        Long warehouseId = ErpServiceValidation.requireId(id, "仓库ID无效");
        String tenantId = actor.tenantId().toString();
        store.delete(tenantId, warehouseId, revision, actor.principalId().toString());
        log.info("ERP仓库逻辑删除完成 tenantId={} warehouseId={} revision={} actorId={}",
                tenantId, warehouseId, revision, actor.principalId());
    }

    private static InternalInventoryWarehouseCommand normalize(
            InternalInventoryWarehouseCommand command, boolean update) {
        if (command == null) throw badRequest("仓库参数不能为空");
        ErpServiceValidation.checkRevision(command.revision(), update);
        return new InternalInventoryWarehouseCommand(
                ErpServiceValidation.required(command.warehouseName(), "warehouseName不能为空", 120),
                ErpServiceValidation.code(command.regionCode(), "regionCode", false),
                ErpServiceValidation.defaultCode(command.warehouseTypeCode(), "warehouseTypeCode", "CITY"),
                command.defaultFlag() != null && command.defaultFlag(),
                ErpServiceValidation.text(command.address(), 1000, "address"),
                ErpServiceValidation.text(command.contactName(), 100, "contactName"),
                ErpServiceValidation.text(command.contactPhone(), 50, "contactPhone"),
                ErpServiceValidation.defaultCode(command.statusCode(), "statusCode", "ACTIVE"),
                ErpServiceValidation.text(command.remark(), 1000, "remark"),
                update ? command.revision() : 0);
    }

    private static CallerIdentity actor(String permission) {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(permission);
        return caller;
    }

    private static String tenant(String permission) {
        return actor(permission).tenantId().toString();
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}
