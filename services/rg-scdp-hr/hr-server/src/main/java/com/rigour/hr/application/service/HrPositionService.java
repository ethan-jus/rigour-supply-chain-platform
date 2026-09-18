package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.HrPageView;
import com.rigour.hr.api.v1.model.HrPositionCommand;
import com.rigour.hr.api.v1.model.HrPositionView;
import com.rigour.hr.application.port.out.HrPositionStore;
import com.rigour.hr.application.port.out.HrPositionStore.PositionSearchCriteria;
import com.rigour.hr.application.service.support.HrServiceValidation;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** HR 岗位维护与租户权限校验。 */
@Service
public final class HrPositionService {
    private static final Logger log = LoggerFactory.getLogger(HrPositionService.class);
    private static final String READ_PERMISSION = "hr:position:read";
    private static final String WRITE_PERMISSION = "hr:position:write";

    private final HrPositionStore store;
    public HrPositionService(HrPositionStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    private static String requiredCode(String value) {
        String code = HrServiceValidation.required(value, "岗位编码不能为空", 50);
        if (!code.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,49}")) throw badRequest("岗位编码只能使用字母、数字、下划线和短横线");
        return code;
    }

    private static int sortOrder(Integer value) {
        if (value == null || value < 0) throw badRequest("岗位顺序不能为空且不能小于0");
        return value;
    }

    public HrPageView<HrPositionView> positions(int begin, int step,
                                                String positionCode, String positionName,
                                                String statusCode) {
        String tenantId = tenant(READ_PERMISSION);
        PositionSearchCriteria criteria = new PositionSearchCriteria(
                HrServiceValidation.text(positionCode, 50, "positionCode"),
                HrServiceValidation.text(positionName, 120, "positionName"),
                statusCode(statusCode));
        HrPageView<HrPositionView> result = store.positions(
                tenantId, HrServiceValidation.pageBegin(begin), HrServiceValidation.pageStep(step), criteria);
        log.debug("HR岗位列表查询完成 tenantId={} positionCode={} positionName={} count={} total={}",
                tenantId, HrServiceValidation.value(criteria.positionCode()),
                HrServiceValidation.value(criteria.positionName()), result.items().size(), result.total());
        return result;
    }

    public HrPositionView position(Long id) {
        String tenantId = tenant(READ_PERMISSION);
        HrPositionView result = store.position(tenantId, HrServiceValidation.requireId(id, "岗位ID无效"))
                .orElseThrow(() -> notFound("岗位不存在"));
        log.debug("HR岗位详情查询完成 tenantId={} positionId={} positionCode={}",
                tenantId, result.id(), result.positionCode());
        return result;
    }

    public HrPositionView create(HrPositionCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        HrPositionCommand normalized = normalize(command, false);
        String tenantId = actor.tenantId().toString();
        String positionCode = normalized.positionCode();
        if (store.existsByPositionCode(tenantId, positionCode)) throw new BusinessException(ErrorCode.CONFLICT, "岗位编码已存在", List.of());
        HrPositionView created = store.create(
                tenantId, positionCode, normalized, actor.principalId().toString());
        log.info("HR岗位创建完成 tenantId={} positionId={} positionCode={} actorId={}",
                tenantId, created.id(), created.positionCode(), actor.principalId());
        return created;
    }

    public HrPositionView update(Long id, HrPositionCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        HrPositionCommand normalized = normalize(command, true);
        String tenantId = actor.tenantId().toString();
        HrPositionView updated = store.update(tenantId,
                HrServiceValidation.requireId(id, "岗位ID无效"),
                normalized, actor.principalId().toString());
        log.info("HR岗位修改完成 tenantId={} positionId={} positionCode={} revision={} actorId={}",
                tenantId, updated.id(), updated.positionCode(), updated.revision(), actor.principalId());
        return updated;
    }

    public void delete(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        if (revision < 1) throw badRequest("revision必须大于0");
        String tenantId = actor.tenantId().toString();
        store.delete(tenantId, HrServiceValidation.requireId(id, "岗位ID无效"),
                revision, actor.principalId().toString());
        log.info("HR岗位逻辑删除完成 tenantId={} positionId={} revision={} actorId={}",
                tenantId, id, revision, actor.principalId());
    }

    private static HrPositionCommand normalize(HrPositionCommand command, boolean update) {
        if (command == null) throw badRequest("岗位参数不能为空");
        Integer revision = command.revision();
        if (update && (revision == null || revision < 1)) throw badRequest("revision必须大于0");
        if (!update && revision != null && revision != 0) throw badRequest("新增岗位时revision必须为空或0");
        return new HrPositionCommand(
                HrServiceValidation.required(command.positionName(), "positionName不能为空", 120),
                statusCode(command.statusCode(), true),
                HrServiceValidation.text(command.remark(), 500, "remark"),
                update ? revision : 0,
                requiredCode(command.positionCode()),
                sortOrder(command.sortOrder()));
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

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}
