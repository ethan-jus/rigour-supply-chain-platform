package com.rigour.erp.application.service.product;

import com.rigour.erp.api.v1.model.InternalProductCategoryCommand;
import com.rigour.erp.api.v1.model.InternalProductCategoryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpProductCategoryStore;
import com.rigour.erp.application.port.out.ErpProductCategoryStore.CategorySearchCriteria;
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

/** ERP 商品分类维护用例；分类编码后端生成，删除只做逻辑删除。 */
@Service
public final class ErpProductCategoryService {
    private static final Logger log = LoggerFactory.getLogger(ErpProductCategoryService.class);
    private static final String READ_PERMISSION = "erp:product:read";
    private static final String WRITE_PERMISSION = "erp:product:write";

    private final ErpProductCategoryStore store;
    private final BusinessCodeGenerator codeGenerator;

    @Autowired
    public ErpProductCategoryService(ErpProductCategoryStore store) {
        this(store, new BusinessCodeGenerator());
    }

    ErpProductCategoryService(ErpProductCategoryStore store, BusinessCodeGenerator codeGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public MasterDataPageView<InternalProductCategoryView> categories(
            int begin, int step, String categoryCode, String categoryName, Long parentId) {
        String tenantId = tenant(READ_PERMISSION);
        CategorySearchCriteria criteria = new CategorySearchCriteria(
                ErpServiceValidation.text(categoryCode, 50, "categoryCode"),
                ErpServiceValidation.text(categoryName, 120, "categoryName"),
                ErpServiceValidation.optionalId(parentId, "parentId"));
        MasterDataPageView<InternalProductCategoryView> result = store.categories(
                tenantId, ErpServiceValidation.pageBegin(begin), ErpServiceValidation.pageStep(step), criteria);
        log.debug("ERP商品分类列表查询完成 tenantId={} categoryCode={} categoryName={} parentId={} count={} total={}",
                tenantId, ErpServiceValidation.value(criteria.categoryCode()),
                ErpServiceValidation.value(criteria.categoryName()), criteria.parentId(),
                result.items().size(), result.total());
        return result;
    }

    public InternalProductCategoryView category(Long id) {
        String tenantId = tenant(READ_PERMISSION);
        InternalProductCategoryView result = store.category(tenantId, ErpServiceValidation.requireId(id, "分类ID无效"))
                .orElseThrow(() -> notFound("商品分类不存在"));
        log.debug("ERP商品分类详情查询完成 tenantId={} categoryId={} categoryCode={}",
                tenantId, result.id(), result.categoryCode());
        return result;
    }

    public InternalProductCategoryView create(InternalProductCategoryCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        InternalProductCategoryCommand normalized = normalize(command, false);
        String tenantId = actor.tenantId().toString();
        int categoryLevel = categoryLevel(tenantId, normalized.parentId(), null);
        String categoryCode = codeGenerator.generateUnique(ErpBusinessCodeRules.CATEGORY,
                candidate -> !store.existsByCode(tenantId, candidate));
        InternalProductCategoryView created = store.create(
                tenantId, categoryCode, normalized, categoryLevel, actor.principalId().toString());
        log.info("ERP商品分类创建完成 tenantId={} categoryId={} categoryCode={} categoryName={} actorId={}",
                tenantId, created.id(), created.categoryCode(), created.categoryName(), actor.principalId());
        return created;
    }

    public InternalProductCategoryView update(Long id, InternalProductCategoryCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        Long categoryId = ErpServiceValidation.requireId(id, "分类ID无效");
        InternalProductCategoryCommand normalized = normalize(command, true);
        String tenantId = actor.tenantId().toString();
        int categoryLevel = categoryLevel(tenantId, normalized.parentId(), categoryId);
        InternalProductCategoryView updated = store.update(
                tenantId, categoryId, normalized, categoryLevel, actor.principalId().toString());
        log.info("ERP商品分类修改完成 tenantId={} categoryId={} categoryCode={} revision={} actorId={}",
                tenantId, updated.id(), updated.categoryCode(), updated.revision(), actor.principalId());
        return updated;
    }

    public void delete(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        Long categoryId = ErpServiceValidation.requireId(id, "分类ID无效");
        ErpServiceValidation.requireRevision(revision);
        String tenantId = actor.tenantId().toString();
        if (store.hasChildren(tenantId, categoryId)) {
            throw conflict("商品分类存在子分类，不能直接删除");
        }
        store.delete(tenantId, categoryId, revision, actor.principalId().toString());
        log.info("ERP商品分类逻辑删除完成 tenantId={} categoryId={} revision={} actorId={}",
                tenantId, categoryId, revision, actor.principalId());
    }

    private int categoryLevel(String tenantId, Long parentId, Long currentId) {
        if (parentId == null) return 1;
        if (currentId != null && currentId.equals(parentId)) {
            throw conflict("父分类不能选择当前分类");
        }
        if (currentId != null && store.hasAncestor(tenantId, parentId, currentId)) {
            throw conflict("父分类不能选择当前分类的子分类");
        }
        InternalProductCategoryView parent = store.category(tenantId, parentId)
                .orElseThrow(() -> notFound("父级商品分类不存在"));
        return parent.categoryLevel() + 1;
    }

    private static InternalProductCategoryCommand normalize(InternalProductCategoryCommand command, boolean update) {
        if (command == null) throw badRequest("商品分类参数不能为空");
        ErpServiceValidation.checkRevision(command.revision(), update);
        return new InternalProductCategoryCommand(
                ErpServiceValidation.optionalId(command.parentId(), "parentId"),
                ErpServiceValidation.required(command.categoryName(), "categoryName不能为空", 120),
                ErpServiceValidation.ordinal(command.ordinal()),
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

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}
