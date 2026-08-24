package com.rigour.erp.application.service.product;

import com.rigour.erp.api.v1.model.InternalProductTagCommand;
import com.rigour.erp.api.v1.model.InternalProductTagView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpProductTagStore;
import com.rigour.erp.application.port.out.ErpProductTagStore.TagSearchCriteria;
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

/** ERP 商品标签维护用例；标签类型由字典统一管理。 */
@Service
public final class ErpProductTagService {
    private static final Logger log = LoggerFactory.getLogger(ErpProductTagService.class);
    private static final String READ_PERMISSION = "erp:product:read";
    private static final String WRITE_PERMISSION = "erp:product:write";

    private final ErpProductTagStore store;
    private final BusinessCodeGenerator codeGenerator;

    @Autowired
    public ErpProductTagService(ErpProductTagStore store) {
        this(store, new BusinessCodeGenerator());
    }

    ErpProductTagService(ErpProductTagStore store, BusinessCodeGenerator codeGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public MasterDataPageView<InternalProductTagView> tags(
            int begin, int step, String tagCode, String tagName, String tagTypeCode) {
        String tenantId = tenant(READ_PERMISSION);
        TagSearchCriteria criteria = new TagSearchCriteria(
                ErpServiceValidation.text(tagCode, 50, "tagCode"),
                ErpServiceValidation.text(tagName, 120, "tagName"),
                ErpServiceValidation.code(tagTypeCode, "tagTypeCode", false));
        MasterDataPageView<InternalProductTagView> result = store.tags(
                tenantId, ErpServiceValidation.pageBegin(begin), ErpServiceValidation.pageStep(step), criteria);
        log.debug("ERP商品标签列表查询完成 tenantId={} tagCode={} tagName={} tagTypeCode={} count={} total={}",
                tenantId, ErpServiceValidation.value(criteria.tagCode()),
                ErpServiceValidation.value(criteria.tagName()), ErpServiceValidation.value(criteria.tagTypeCode()),
                result.items().size(), result.total());
        return result;
    }

    public InternalProductTagView tag(Long id) {
        String tenantId = tenant(READ_PERMISSION);
        InternalProductTagView result = store.tag(tenantId, ErpServiceValidation.requireId(id, "标签ID无效"))
                .orElseThrow(() -> notFound("商品标签不存在"));
        log.debug("ERP商品标签详情查询完成 tenantId={} tagId={} tagCode={}",
                tenantId, result.id(), result.tagCode());
        return result;
    }

    public InternalProductTagView create(InternalProductTagCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        InternalProductTagCommand normalized = normalize(command, false);
        String tenantId = actor.tenantId().toString();
        String tagCode = codeGenerator.generateUnique(ErpBusinessCodeRules.TAG,
                candidate -> !store.existsByCode(tenantId, candidate));
        InternalProductTagView created = store.create(
                tenantId, tagCode, normalized, actor.principalId().toString());
        log.info("ERP商品标签创建完成 tenantId={} tagId={} tagCode={} tagName={} actorId={}",
                tenantId, created.id(), created.tagCode(), created.tagName(), actor.principalId());
        return created;
    }

    public InternalProductTagView update(Long id, InternalProductTagCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        InternalProductTagCommand normalized = normalize(command, true);
        String tenantId = actor.tenantId().toString();
        InternalProductTagView updated = store.update(tenantId,
                ErpServiceValidation.requireId(id, "标签ID无效"), normalized, actor.principalId().toString());
        log.info("ERP商品标签修改完成 tenantId={} tagId={} tagCode={} revision={} actorId={}",
                tenantId, updated.id(), updated.tagCode(), updated.revision(), actor.principalId());
        return updated;
    }

    public void delete(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        ErpServiceValidation.requireRevision(revision);
        Long tagId = ErpServiceValidation.requireId(id, "标签ID无效");
        String tenantId = actor.tenantId().toString();
        store.delete(tenantId, tagId, revision, actor.principalId().toString());
        log.info("ERP商品标签逻辑删除完成 tenantId={} tagId={} revision={} actorId={}",
                tenantId, tagId, revision, actor.principalId());
    }

    private static InternalProductTagCommand normalize(InternalProductTagCommand command, boolean update) {
        if (command == null) throw badRequest("商品标签参数不能为空");
        ErpServiceValidation.checkRevision(command.revision(), update);
        return new InternalProductTagCommand(
                ErpServiceValidation.required(command.tagName(), "tagName不能为空", 120),
                ErpServiceValidation.code(command.tagTypeCode(), "tagTypeCode", false),
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
