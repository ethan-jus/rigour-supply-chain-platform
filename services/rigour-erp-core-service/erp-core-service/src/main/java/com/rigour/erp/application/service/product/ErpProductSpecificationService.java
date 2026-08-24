package com.rigour.erp.application.service.product;

import com.rigour.erp.api.v1.model.InternalProductSpecificationCommand;
import com.rigour.erp.api.v1.model.InternalProductSpecificationValueCommand;
import com.rigour.erp.api.v1.model.InternalProductSpecificationView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpProductSpecificationStore;
import com.rigour.erp.application.port.out.ErpProductSpecificationStore.SpecificationSearchCriteria;
import com.rigour.erp.application.service.support.ErpServiceValidation;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** ERP 商品规格维护用例；一个规格下维护多个子规格值，删除统一逻辑删除。 */
@Service
public final class ErpProductSpecificationService {
    private static final Logger log = LoggerFactory.getLogger(ErpProductSpecificationService.class);
    private static final Pattern BUSINESS_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,49}");
    private static final String READ_PERMISSION = "erp:product:read";
    private static final String WRITE_PERMISSION = "erp:product:write";
    private static final String ACTIVE = "ACTIVE";

    private final ErpProductSpecificationStore store;

    public ErpProductSpecificationService(ErpProductSpecificationStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public MasterDataPageView<InternalProductSpecificationView> specifications(
            int begin, int step, String specificationCode, String specificationName, String statusCode) {
        String tenantId = tenant(READ_PERMISSION);
        SpecificationSearchCriteria criteria = new SpecificationSearchCriteria(
                optionalBusinessCode(specificationCode, "specificationCode"),
                ErpServiceValidation.text(specificationName, 120, "specificationName"),
                ErpServiceValidation.code(statusCode, "statusCode", false));
        MasterDataPageView<InternalProductSpecificationView> result = store.specifications(
                tenantId, ErpServiceValidation.pageBegin(begin), ErpServiceValidation.pageStep(step), criteria);
        log.debug("ERP商品规格列表查询完成 tenantId={} specificationCode={} specificationName={} statusCode={} count={} total={}",
                tenantId, ErpServiceValidation.value(criteria.specificationCode()),
                ErpServiceValidation.value(criteria.specificationName()),
                ErpServiceValidation.value(criteria.statusCode()), result.items().size(), result.total());
        return result;
    }

    public InternalProductSpecificationView specification(Long id) {
        String tenantId = tenant(READ_PERMISSION);
        InternalProductSpecificationView result = store.specification(
                        tenantId, ErpServiceValidation.requireId(id, "商品规格ID无效"))
                .orElseThrow(() -> notFound("商品规格不存在"));
        log.debug("ERP商品规格详情查询完成 tenantId={} specificationId={} specificationCode={} valueCount={}",
                tenantId, result.id(), result.specificationCode(), result.valueCount());
        return result;
    }

    public InternalProductSpecificationView create(InternalProductSpecificationCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        InternalProductSpecificationCommand normalized = normalize(command, false);
        String tenantId = actor.tenantId().toString();
        if (store.existsByCode(tenantId, normalized.specificationCode(), null)) {
            throw conflict("商品规格编号已存在");
        }
        InternalProductSpecificationView created = store.create(
                tenantId, normalized, actor.principalId().toString());
        log.info("ERP商品规格创建完成 tenantId={} specificationId={} specificationCode={} specificationName={} valueCount={} actorId={}",
                tenantId, created.id(), created.specificationCode(), created.specificationName(),
                created.valueCount(), actor.principalId());
        return created;
    }

    public InternalProductSpecificationView update(Long id, InternalProductSpecificationCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        Long specificationId = ErpServiceValidation.requireId(id, "商品规格ID无效");
        InternalProductSpecificationCommand normalized = normalize(command, true);
        String tenantId = actor.tenantId().toString();
        if (store.existsByCode(tenantId, normalized.specificationCode(), specificationId)) {
            throw conflict("商品规格编号已存在");
        }
        InternalProductSpecificationView updated = store.update(
                tenantId, specificationId, normalized, actor.principalId().toString());
        log.info("ERP商品规格修改完成 tenantId={} specificationId={} specificationCode={} revision={} valueCount={} actorId={}",
                tenantId, updated.id(), updated.specificationCode(), updated.revision(),
                updated.valueCount(), actor.principalId());
        return updated;
    }

    public void delete(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        Long specificationId = ErpServiceValidation.requireId(id, "商品规格ID无效");
        ErpServiceValidation.requireRevision(revision);
        String tenantId = actor.tenantId().toString();
        store.delete(tenantId, specificationId, revision, actor.principalId().toString());
        log.info("ERP商品规格逻辑删除完成 tenantId={} specificationId={} revision={} actorId={}",
                tenantId, specificationId, revision, actor.principalId());
    }

    private static InternalProductSpecificationCommand normalize(
            InternalProductSpecificationCommand command, boolean update) {
        if (command == null) throw badRequest("商品规格参数不能为空");
        ErpServiceValidation.checkRevision(command.revision(), update);
        String specificationCode = requiredBusinessCode(command.specificationCode(), "specificationCode");
        List<InternalProductSpecificationValueCommand> values = normalizeValues(command.values());
        if (values.isEmpty()) throw badRequest("商品规格至少需要一个规格值");
        return new InternalProductSpecificationCommand(
                specificationCode,
                ErpServiceValidation.required(command.specificationName(), "specificationName不能为空", 120),
                ErpServiceValidation.defaultCode(command.statusCode(), "statusCode", ACTIVE),
                values,
                update ? command.revision() : 0);
    }

    private static List<InternalProductSpecificationValueCommand> normalizeValues(
            List<InternalProductSpecificationValueCommand> source) {
        List<InternalProductSpecificationValueCommand> result = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        Set<String> codes = new HashSet<>();
        int index = 1;
        for (InternalProductSpecificationValueCommand item : source == null ? List.<InternalProductSpecificationValueCommand>of() : source) {
            if (item == null) continue;
            Long id = ErpServiceValidation.optionalId(item.id(), "valueId");
            if (id != null && !ids.add(id)) throw badRequest("规格值不能重复提交");
            String valueCode = optionalBusinessCode(item.valueCode(), "valueCode");
            if (valueCode == null) valueCode = "V" + String.format(Locale.ROOT, "%03d", index);
            if (!codes.add(valueCode)) throw badRequest("规格值编号不能重复");
            result.add(new InternalProductSpecificationValueCommand(
                    id,
                    valueCode,
                    ErpServiceValidation.required(item.valueName(), "valueName不能为空", 120),
                    ErpServiceValidation.ordinal(item.ordinal()),
                    ErpServiceValidation.defaultCode(item.statusCode(), "statusCode", ACTIVE)));
            index++;
        }
        return result;
    }

    private static String requiredBusinessCode(String value, String name) {
        String normalized = optionalBusinessCode(value, name);
        if (normalized == null) throw badRequest(name + "不能为空");
        return normalized;
    }

    private static String optionalBusinessCode(String value, String name) {
        String normalized = ErpServiceValidation.text(value, 50, name);
        if (normalized == null) return null;
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!BUSINESS_CODE.matcher(normalized).matches()) {
            throw badRequest(name + "只能包含字母、数字、下划线或中横线，且长度不能超过50");
        }
        return normalized;
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
