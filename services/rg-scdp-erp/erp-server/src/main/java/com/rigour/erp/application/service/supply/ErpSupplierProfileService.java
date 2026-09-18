package com.rigour.erp.application.service.supply;

import com.rigour.erp.api.v1.model.InternalSupplierProfileCommand;
import com.rigour.erp.api.v1.model.InternalSupplierProfileView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpSupplierProfileStore;
import com.rigour.erp.application.port.out.ErpSupplierProfileStore.SupplierSearchCriteria;
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

/** ERP 供应商档案维护用例；删除统一逻辑删，启停通过 statusCode 表达。 */
@Service
public final class ErpSupplierProfileService {
    private static final Logger log = LoggerFactory.getLogger(ErpSupplierProfileService.class);
    private static final String READ_PERMISSION = "erp:supply:read";
    private static final String WRITE_PERMISSION = "erp:supply:write";
    private static final String ACTIVE = "ACTIVE";

    private final ErpSupplierProfileStore store;
    private final BusinessCodeGenerator codeGenerator;

    @Autowired
    public ErpSupplierProfileService(ErpSupplierProfileStore store) {
        this(store, new BusinessCodeGenerator());
    }

    ErpSupplierProfileService(ErpSupplierProfileStore store, BusinessCodeGenerator codeGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public MasterDataPageView<InternalSupplierProfileView> suppliers(
            int begin, int step, String supplierCode, String supplierName, String contactPhone, String statusCode) {
        String tenantId = tenant(READ_PERMISSION);
        SupplierSearchCriteria criteria = new SupplierSearchCriteria(
                ErpServiceValidation.text(supplierCode, 50, "supplierCode"),
                ErpServiceValidation.text(supplierName, 200, "supplierName"),
                ErpServiceValidation.text(contactPhone, 50, "contactPhone"),
                ErpServiceValidation.code(statusCode, "statusCode", false));
        MasterDataPageView<InternalSupplierProfileView> result = store.suppliers(
                tenantId, ErpServiceValidation.pageBegin(begin), ErpServiceValidation.pageStep(step), criteria);
        log.debug("ERP供应商列表查询完成 tenantId={} supplierCode={} supplierName={} contactPhone={} statusCode={} count={} total={}",
                tenantId, ErpServiceValidation.value(criteria.supplierCode()),
                ErpServiceValidation.value(criteria.supplierName()),
                ErpServiceValidation.value(criteria.contactPhone()),
                ErpServiceValidation.value(criteria.statusCode()), result.items().size(), result.total());
        return result;
    }

    public InternalSupplierProfileView supplier(Long id) {
        String tenantId = tenant(READ_PERMISSION);
        InternalSupplierProfileView result = store.supplier(tenantId,
                        ErpServiceValidation.requireId(id, "供应商ID无效"))
                .orElseThrow(() -> notFound("供应商不存在"));
        log.debug("ERP供应商详情查询完成 tenantId={} supplierId={} supplierCode={}",
                tenantId, result.id(), result.supplierCode());
        return result;
    }

    public InternalSupplierProfileView create(InternalSupplierProfileCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        InternalSupplierProfileCommand normalized = normalize(command, false);
        String tenantId = actor.tenantId().toString();
        String supplierCode = codeGenerator.generateUnique(ErpBusinessCodeRules.SUPPLIER,
                candidate -> !store.existsByCode(tenantId, candidate));
        InternalSupplierProfileView created = store.create(
                tenantId, supplierCode, normalized, actor.principalId().toString());
        log.info("ERP供应商创建完成 tenantId={} supplierId={} supplierCode={} supplierName={} actorId={}",
                tenantId, created.id(), created.supplierCode(), created.supplierName(), actor.principalId());
        return created;
    }

    public InternalSupplierProfileView update(Long id, InternalSupplierProfileCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        InternalSupplierProfileCommand normalized = normalize(command, true);
        String tenantId = actor.tenantId().toString();
        InternalSupplierProfileView updated = store.update(tenantId,
                ErpServiceValidation.requireId(id, "供应商ID无效"), normalized, actor.principalId().toString());
        log.info("ERP供应商修改完成 tenantId={} supplierId={} supplierCode={} revision={} actorId={}",
                tenantId, updated.id(), updated.supplierCode(), updated.revision(), actor.principalId());
        return updated;
    }

    public void delete(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        ErpServiceValidation.requireRevision(revision);
        Long supplierId = ErpServiceValidation.requireId(id, "供应商ID无效");
        String tenantId = actor.tenantId().toString();
        store.delete(tenantId, supplierId, revision, actor.principalId().toString());
        log.info("ERP供应商逻辑删除完成 tenantId={} supplierId={} revision={} actorId={}",
                tenantId, supplierId, revision, actor.principalId());
    }

    private static InternalSupplierProfileCommand normalize(InternalSupplierProfileCommand command, boolean update) {
        if (command == null) throw badRequest("供应商参数不能为空");
        ErpServiceValidation.checkRevision(command.revision(), update);
        return new InternalSupplierProfileCommand(
                ErpServiceValidation.required(command.supplierName(), "supplierName不能为空", 200),
                ErpServiceValidation.text(command.contactName(), 100, "contactName"),
                ErpServiceValidation.text(command.contactPhone(), 50, "contactPhone"),
                ErpServiceValidation.text(command.address(), 1000, "address"),
                ErpServiceValidation.text(command.bankName(), 100, "bankName"),
                ErpServiceValidation.text(command.bankAccountNo(), 100, "bankAccountNo"),
                ErpServiceValidation.defaultCode(command.statusCode(), "statusCode", ACTIVE),
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
