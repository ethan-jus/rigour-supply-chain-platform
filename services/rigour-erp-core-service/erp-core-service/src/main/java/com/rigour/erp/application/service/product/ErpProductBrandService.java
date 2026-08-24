package com.rigour.erp.application.service.product;

import com.rigour.erp.api.v1.model.InternalProductBrandCommand;
import com.rigour.erp.api.v1.model.InternalProductBrandView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpProductBrandStore;
import com.rigour.erp.application.port.out.ErpProductBrandStore.BrandSearchCriteria;
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

/** ERP 商品品牌维护用例；品牌删除只做逻辑删除。 */
@Service
public final class ErpProductBrandService {
    private static final Logger log = LoggerFactory.getLogger(ErpProductBrandService.class);
    private static final String READ_PERMISSION = "erp:product:read";
    private static final String WRITE_PERMISSION = "erp:product:write";

    private final ErpProductBrandStore store;
    private final BusinessCodeGenerator codeGenerator;

    @Autowired
    public ErpProductBrandService(ErpProductBrandStore store) {
        this(store, new BusinessCodeGenerator());
    }

    ErpProductBrandService(ErpProductBrandStore store, BusinessCodeGenerator codeGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public MasterDataPageView<InternalProductBrandView> brands(int begin, int step,
                                                               String brandCode, String brandName) {
        String tenantId = tenant(READ_PERMISSION);
        BrandSearchCriteria criteria = new BrandSearchCriteria(
                ErpServiceValidation.text(brandCode, 50, "brandCode"),
                ErpServiceValidation.text(brandName, 120, "brandName"));
        MasterDataPageView<InternalProductBrandView> result = store.brands(
                tenantId, ErpServiceValidation.pageBegin(begin), ErpServiceValidation.pageStep(step), criteria);
        log.debug("ERP商品品牌列表查询完成 tenantId={} brandCode={} brandName={} count={} total={}",
                tenantId, ErpServiceValidation.value(criteria.brandCode()),
                ErpServiceValidation.value(criteria.brandName()), result.items().size(), result.total());
        return result;
    }

    public InternalProductBrandView brand(Long id) {
        String tenantId = tenant(READ_PERMISSION);
        InternalProductBrandView result = store.brand(tenantId, ErpServiceValidation.requireId(id, "品牌ID无效"))
                .orElseThrow(() -> notFound("商品品牌不存在"));
        log.debug("ERP商品品牌详情查询完成 tenantId={} brandId={} brandCode={}",
                tenantId, result.id(), result.brandCode());
        return result;
    }

    public InternalProductBrandView create(InternalProductBrandCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        InternalProductBrandCommand normalized = normalize(command, false);
        String tenantId = actor.tenantId().toString();
        String brandCode = codeGenerator.generateUnique(ErpBusinessCodeRules.BRAND,
                candidate -> !store.existsByCode(tenantId, candidate));
        InternalProductBrandView created = store.create(
                tenantId, brandCode, normalized, actor.principalId().toString());
        log.info("ERP商品品牌创建完成 tenantId={} brandId={} brandCode={} brandName={} actorId={}",
                tenantId, created.id(), created.brandCode(), created.brandName(), actor.principalId());
        return created;
    }

    public InternalProductBrandView update(Long id, InternalProductBrandCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        InternalProductBrandCommand normalized = normalize(command, true);
        String tenantId = actor.tenantId().toString();
        InternalProductBrandView updated = store.update(tenantId,
                ErpServiceValidation.requireId(id, "品牌ID无效"), normalized, actor.principalId().toString());
        log.info("ERP商品品牌修改完成 tenantId={} brandId={} brandCode={} revision={} actorId={}",
                tenantId, updated.id(), updated.brandCode(), updated.revision(), actor.principalId());
        return updated;
    }

    public void delete(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        ErpServiceValidation.requireRevision(revision);
        Long brandId = ErpServiceValidation.requireId(id, "品牌ID无效");
        String tenantId = actor.tenantId().toString();
        store.delete(tenantId, brandId, revision, actor.principalId().toString());
        log.info("ERP商品品牌逻辑删除完成 tenantId={} brandId={} revision={} actorId={}",
                tenantId, brandId, revision, actor.principalId());
    }

    private static InternalProductBrandCommand normalize(InternalProductBrandCommand command, boolean update) {
        if (command == null) throw badRequest("商品品牌参数不能为空");
        ErpServiceValidation.checkRevision(command.revision(), update);
        return new InternalProductBrandCommand(
                ErpServiceValidation.required(command.brandName(), "brandName不能为空", 120),
                ErpServiceValidation.text(command.remark(), 500, "remark"),
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
