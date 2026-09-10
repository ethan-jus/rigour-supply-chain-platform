package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.HrPageView;
import com.rigour.hr.api.v1.model.HrPositionCommand;
import com.rigour.hr.api.v1.model.HrPositionView;
import com.rigour.hr.application.port.out.HrPositionStore;
import com.rigour.hr.application.port.out.HrPositionStore.PositionSearchCriteria;
import com.rigour.hr.application.service.support.HrServiceValidation;
import com.rigour.hr.domain.code.HrBusinessCodeRules;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** HR 岗位职位查询用例；写入由员工导入和后续 HR 维护流程负责。 */
@Service
public final class HrPositionService {
    private static final Logger log = LoggerFactory.getLogger(HrPositionService.class);
    private static final String READ_PERMISSION = "hr:position:read";
    private static final String WRITE_PERMISSION = "hr:position:write";

    private final HrPositionStore store;
    private final BusinessCodeGenerator codeGenerator;

    @Autowired
    public HrPositionService(HrPositionStore store) {
        this(store, new BusinessCodeGenerator());
    }

    HrPositionService(HrPositionStore store, BusinessCodeGenerator codeGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public HrPageView<HrPositionView> positions(int begin, int step,
                                                String positionCode, String positionName,
                                                String positionType, String statusCode,
                                                String sourceSystem) {
        String tenantId = tenant(READ_PERMISSION);
        PositionSearchCriteria criteria = new PositionSearchCriteria(
                HrServiceValidation.text(positionCode, 50, "positionCode"),
                HrServiceValidation.text(positionName, 120, "positionName"),
                positionType(positionType),
                statusCode(statusCode),
                sourceSystem(sourceSystem));
        HrPageView<HrPositionView> result = store.positions(
                tenantId, HrServiceValidation.pageBegin(begin), HrServiceValidation.pageStep(step), criteria);
        log.debug("HR岗位职位列表查询完成 tenantId={} positionCode={} positionName={} count={} total={}",
                tenantId, HrServiceValidation.value(criteria.positionCode()),
                HrServiceValidation.value(criteria.positionName()), result.items().size(), result.total());
        return result;
    }

    public HrPositionView position(Long id) {
        String tenantId = tenant(READ_PERMISSION);
        HrPositionView result = store.position(tenantId, HrServiceValidation.requireId(id, "岗位职位ID无效"))
                .orElseThrow(() -> notFound("岗位职位不存在"));
        log.debug("HR岗位职位详情查询完成 tenantId={} positionId={} positionCode={}",
                tenantId, result.id(), result.positionCode());
        return result;
    }

    public HrPositionView create(HrPositionCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        HrPositionCommand normalized = normalize(command, false);
        String tenantId = actor.tenantId().toString();
        String positionCode = codeGenerator.generateUnique(HrBusinessCodeRules.POSITION,
                candidate -> !store.existsByPositionCode(tenantId, candidate));
        HrPositionView created = store.create(
                tenantId, positionCode, normalized, actor.principalId().toString());
        log.info("HR岗位职位创建完成 tenantId={} positionId={} positionCode={} positionType={} actorId={}",
                tenantId, created.id(), created.positionCode(), created.positionType(), actor.principalId());
        return created;
    }

    public HrPositionView update(Long id, HrPositionCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        HrPositionCommand normalized = normalize(command, true);
        String tenantId = actor.tenantId().toString();
        HrPositionView updated = store.update(tenantId,
                HrServiceValidation.requireId(id, "岗位职位ID无效"),
                normalized, actor.principalId().toString());
        log.info("HR岗位职位修改完成 tenantId={} positionId={} positionCode={} revision={} actorId={}",
                tenantId, updated.id(), updated.positionCode(), updated.revision(), actor.principalId());
        return updated;
    }

    public void delete(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        if (revision < 1) throw badRequest("revision必须大于0");
        String tenantId = actor.tenantId().toString();
        store.delete(tenantId, HrServiceValidation.requireId(id, "岗位职位ID无效"),
                revision, actor.principalId().toString());
        log.info("HR岗位职位逻辑删除完成 tenantId={} positionId={} revision={} actorId={}",
                tenantId, id, revision, actor.principalId());
    }

    private static HrPositionCommand normalize(HrPositionCommand command, boolean update) {
        if (command == null) throw badRequest("岗位职位参数不能为空");
        Integer revision = command.revision();
        if (update && (revision == null || revision < 1)) throw badRequest("revision必须大于0");
        if (!update && revision != null && revision != 0) throw badRequest("新增岗位职位时revision必须为空或0");
        return new HrPositionCommand(
                HrServiceValidation.required(command.positionName(), "positionName不能为空", 120),
                requiredPositionType(command.positionType()),
                statusCode(command.statusCode(), true),
                sourceSystem(command.sourceSystem(), true),
                HrServiceValidation.text(command.remark(), 500, "remark"),
                update ? revision : 0);
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

    private static String positionType(String value) {
        String text = HrServiceValidation.text(value, 32, "positionType");
        if (text == null) return null;
        return requiredPositionType(text);
    }

    private static String requiredPositionType(String value) {
        String text = HrServiceValidation.text(value, 32, "positionType");
        if (text == null) throw badRequest("positionType不能为空");
        String upper = text.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "JOB_CATEGORY", "JOB_TITLE" -> upper;
            default -> throw badRequest("positionType格式无效");
        };
    }

    private static String statusCode(String value) {
        return statusCode(value, false);
    }

    private static String statusCode(String value, boolean useDefault) {
        String text = HrServiceValidation.text(value, 32, "statusCode");
        if (text == null) return useDefault ? "ACTIVE" : null;
        String upper = text.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "ACTIVE", "INACTIVE" -> upper;
            default -> throw badRequest("statusCode格式无效");
        };
    }

    private static String sourceSystem(String value) {
        return sourceSystem(value, false);
    }

    private static String sourceSystem(String value, boolean useDefault) {
        String text = HrServiceValidation.text(value, 32, "sourceSystem");
        if (text == null) return useDefault ? "MANUAL" : null;
        String upper = text.toUpperCase(Locale.ROOT);
        if (!upper.matches("[A-Z0-9_]{2,32}")) throw badRequest("sourceSystem格式无效");
        return upper;
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}
