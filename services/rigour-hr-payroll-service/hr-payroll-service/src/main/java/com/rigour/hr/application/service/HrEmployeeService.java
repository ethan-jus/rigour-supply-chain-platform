package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.ExternalEmployeeRowCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeResolveCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeResolvedView;
import com.rigour.hr.api.v1.model.ExternalEmployeeSyncCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeSyncResult;
import com.rigour.hr.api.v1.model.HrEmployeeView;
import com.rigour.hr.api.v1.model.HrPageView;
import com.rigour.hr.application.port.out.HrEmployeeStore;
import com.rigour.hr.application.port.out.HrEmployeeStore.EmployeeSearchCriteria;
import com.rigour.hr.application.service.support.HrServiceValidation;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** HR 员工主档用例；外部导入只写 HR 主档，不创建 IAM 登录账号。 */
@Service
public final class HrEmployeeService {
    private static final Logger log = LoggerFactory.getLogger(HrEmployeeService.class);
    private static final String READ_PERMISSION = "hr:employee:read";
    private static final String SYNC_PERMISSION = "hr:employee:sync";
    private static final int MAX_SYNC_ROWS = 20_000;
    private static final String SYSTEM_ACTOR = "SYSTEM";

    private final HrEmployeeStore store;
    private final BusinessCodeGenerator codeGenerator;

    @Autowired
    public HrEmployeeService(HrEmployeeStore store) {
        this(store, new BusinessCodeGenerator());
    }

    HrEmployeeService(HrEmployeeStore store, BusinessCodeGenerator codeGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public HrPageView<HrEmployeeView> employees(int begin, int step, String keyword,
                                                String employeeCode, String employeeName,
                                                String mobile, String employmentStatus,
                                                String jobCategory, String positionName,
                                                String regionName, String cityName,
                                                String sourceSystem) {
        String tenantId = tenant(READ_PERMISSION);
        EmployeeSearchCriteria criteria = new EmployeeSearchCriteria(
                HrServiceValidation.text(keyword, 128, "keyword"),
                HrServiceValidation.text(employeeCode, 50, "employeeCode"),
                HrServiceValidation.text(employeeName, 128, "employeeName"),
                HrServiceValidation.text(mobile, 32, "mobile"),
                statusFilter(employmentStatus),
                HrServiceValidation.text(jobCategory, 80, "jobCategory"),
                HrServiceValidation.text(positionName, 120, "positionName"),
                HrServiceValidation.text(regionName, 80, "regionName"),
                HrServiceValidation.text(cityName, 80, "cityName"),
                sourceSystem(sourceSystem, false));
        HrPageView<HrEmployeeView> result = store.employees(tenantId, HrServiceValidation.pageBegin(begin),
                HrServiceValidation.pageStep(step), criteria);
        log.debug("HR员工列表查询完成 tenantId={} keyword={} count={} total={}",
                tenantId, HrServiceValidation.value(criteria.keyword()), result.items().size(), result.total());
        return result;
    }

    public HrEmployeeView employee(Long id) {
        String tenantId = tenant(READ_PERMISSION);
        HrEmployeeView result = store.employee(tenantId, HrServiceValidation.requireId(id, "员工ID无效"))
                .orElseThrow(() -> notFound("HR员工不存在"));
        log.debug("HR员工详情查询完成 tenantId={} employeeId={} employeeCode={}",
                tenantId, result.id(), result.employeeCode());
        return result;
    }

    public ExternalEmployeeSyncResult syncExternalEmployees(ExternalEmployeeSyncCommand command) {
        CallerIdentity caller = actor(SYNC_PERMISSION);
        if (command == null) throw badRequest("外部员工同步参数不能为空");
        String sourceSystem = sourceSystem(command.sourceSystem(), true);
        List<ExternalEmployeeRowCommand> rows = normalizeRows(command.rows());
        String tenantId = caller.tenantId().toString();
        String actorId = syncAuditActor(caller);
        ExternalEmployeeSyncResult result = store.syncExternalEmployees(
                tenantId, sourceSystem, rows, actorId, codeGenerator);
        log.info("HR外部员工同步完成 tenantId={} sourceSystem={} received={} created={} updated={} unchanged={} failed={}",
                tenantId, sourceSystem, result.received(), result.created(), result.updated(),
                result.unchanged(), result.failed());
        return result;
    }

    public List<ExternalEmployeeResolvedView> resolveExternalEmployees(ExternalEmployeeResolveCommand command) {
        CallerIdentity caller = actor(READ_PERMISSION);
        if (command == null) throw badRequest("外部员工解析参数不能为空");
        String sourceSystem = sourceSystem(command.sourceSystem(), true);
        String sourceTenantKey = HrServiceValidation.text(command.sourceTenantKey(), 128, "sourceTenantKey");
        List<String> sourceEmployeeIds = command.sourceEmployeeIds();
        List<String> employeeNames = command.employeeNames();
        if ((sourceEmployeeIds == null || sourceEmployeeIds.isEmpty())
                && (employeeNames == null || employeeNames.isEmpty())) {
            return List.of();
        }
        List<ExternalEmployeeResolvedView> result = store.resolveExternalEmployees(
                caller.tenantId().toString(), sourceSystem,
                sourceTenantKey == null ? "DEFAULT" : sourceTenantKey,
                sourceEmployeeIds, employeeNames);
        log.debug("HR外部员工解析完成 tenantId={} sourceSystem={} sourceIds={} names={} resolved={}",
                caller.tenantId(), sourceSystem,
                sourceEmployeeIds == null ? 0 : sourceEmployeeIds.size(),
                employeeNames == null ? 0 : employeeNames.size(), result.size());
        return result;
    }

    private static List<ExternalEmployeeRowCommand> normalizeRows(List<ExternalEmployeeRowCommand> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        if (rows.size() > MAX_SYNC_ROWS) throw badRequest("单次员工同步不能超过" + MAX_SYNC_ROWS + "行");
        List<ExternalEmployeeRowCommand> normalized = new ArrayList<>(rows.size());
        for (ExternalEmployeeRowCommand row : rows) {
            if (row == null) throw badRequest("员工同步行不能为空");
            normalized.add(new ExternalEmployeeRowCommand(
                    row.connectorId(),
                    text(row.sourceTenantKey(), 128),
                    HrServiceValidation.required(row.sourceEmployeeId(), "来源员工ID不能为空", 128),
                    text(row.accountName(), 128),
                    HrServiceValidation.required(row.employeeName(), "员工姓名不能为空", 128),
                    text(row.jobCategory(), 80),
                    text(row.positionName(), 120),
                    text(row.departmentName(), 128),
                    text(row.leaderName(), 128),
                    text(row.regionName(), 80),
                    text(row.cityName(), 80),
                    text(row.mobile(), 32),
                    text(row.email(), 128),
                    text(row.employmentStatus(), 32),
                    row.entryDate(),
                    row.leaveDate(),
                    row.sourceCreatedAt(),
                    row.sourceUpdatedAt(),
                    text(row.sourcePayloadHash(), 64),
                    row.sourcePayloadJson()));
        }
        return List.copyOf(normalized);
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

    private static String syncAuditActor(CallerIdentity caller) {
        return "SERVICE".equals(caller.principalScope()) ? SYSTEM_ACTOR : caller.principalId().toString();
    }

    private static String sourceSystem(String value, boolean required) {
        String text = text(value, 32);
        if (text == null) {
            if (required) throw badRequest("sourceSystem不能为空");
            return null;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        if (!upper.matches("[A-Z0-9_]{2,32}")) {
            throw badRequest("sourceSystem格式无效");
        }
        return upper;
    }

    private static String statusFilter(String value) {
        String text = text(value, 32);
        if (text == null) return null;
        String upper = text.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "ACTIVE", "INACTIVE", "PENDING" -> upper;
            default -> throw badRequest("employmentStatus格式无效");
        };
    }

    private static String text(String value, int max) {
        return HrServiceValidation.text(value, max, "text");
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}
