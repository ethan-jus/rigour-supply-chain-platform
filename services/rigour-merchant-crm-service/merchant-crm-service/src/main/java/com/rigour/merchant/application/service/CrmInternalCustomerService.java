package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.model.ExternalCrmCustomerRowCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerSyncCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerSyncResult;
import com.rigour.merchant.api.v1.model.InternalCustomerCommand;
import com.rigour.merchant.api.v1.model.InternalCustomerDetailView;
import com.rigour.merchant.api.v1.model.InternalCustomerSummaryView;
import com.rigour.merchant.api.v1.model.PageView;
import com.rigour.merchant.application.port.out.CrmInternalCustomerStore;
import com.rigour.merchant.application.port.out.CrmInternalCustomerStore.CustomerSearchCriteria;
import com.rigour.merchant.domain.code.CrmBusinessCodeRules;
import com.rigour.merchant.domain.enums.CrmCustomerStatus;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * CRM 自研客户管理用例。
 *
 * <p>客户、商家、门店统一为客户；本用例是后续销售订单选择客户的唯一业务来源。</p>
 */
@Service
public class CrmInternalCustomerService {
    private static final Logger log = LoggerFactory.getLogger(CrmInternalCustomerService.class);
    private static final String READ_PERMISSION = "crm:customer:read";
    private static final String WRITE_PERMISSION = "crm:customer:write";
    private static final String SYNC_PERMISSION = "crm:customer:sync";
    private static final int MAX_SYNC_ROWS = 20_000;
    private static final String SYSTEM_ACTOR = "SYSTEM";
    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_]{0,63}");
    private static final Pattern SOURCE_SYSTEM = Pattern.compile("[A-Z0-9_]{2,32}");

    private final CrmInternalCustomerStore store;
    private final BusinessCodeGenerator codeGenerator;

    @Autowired
    public CrmInternalCustomerService(CrmInternalCustomerStore store) {
        this(store, new BusinessCodeGenerator());
    }

    CrmInternalCustomerService(CrmInternalCustomerStore store, BusinessCodeGenerator codeGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public PageView<InternalCustomerSummaryView> customers(int begin, int step,
                                                           String customerCode,
                                                           String customerName,
                                                           String contactPhone,
                                                           String customerTypeCode,
                                                           String regionCode,
                                                           String ownerSalesUserId,
                                                           String ownerEmployeeCode,
                                                           String statusCode) {
        String tenantId = tenant(READ_PERMISSION);
        CustomerSearchCriteria criteria = new CustomerSearchCriteria(
                text(customerCode, 50, "customerCode"),
                text(customerName, 200, "customerName"),
                text(contactPhone, 50, "contactPhone"),
                code(customerTypeCode, "customerTypeCode", false),
                code(regionCode, "regionCode", false),
                text(ownerSalesUserId, 64, "ownerSalesUserId"),
                text(ownerEmployeeCode, 50, "ownerEmployeeCode"),
                customerStatus(statusCode, false));
        PageView<InternalCustomerSummaryView> result = store.customers(
                tenantId, pageBegin(begin), pageStep(step), criteria);
        log.debug("CRM自研客户列表查询完成 tenantId={} customerCode={} customerName={} contactPhone={} customerTypeCode={} regionCode={} ownerSalesUserId={} ownerEmployeeCode={} statusCode={} count={} total={}",
                tenantId, value(criteria.customerCode()), value(criteria.customerName()),
                value(criteria.contactPhone()), value(criteria.customerTypeCode()), value(criteria.regionCode()),
                value(criteria.ownerSalesUserId()), value(criteria.ownerEmployeeCode()), value(criteria.statusCode()),
                result.items().size(), result.total());
        return result;
    }

    public InternalCustomerDetailView customer(Long id) {
        String tenantId = tenant(READ_PERMISSION);
        InternalCustomerDetailView result = store.customer(tenantId, requireId(id))
                .orElseThrow(() -> notFound("客户不存在"));
        log.debug("CRM自研客户详情查询完成 tenantId={} customerId={} customerCode={}",
                tenantId, result.id(), result.customerCode());
        return result;
    }

    public InternalCustomerDetailView create(InternalCustomerCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        InternalCustomerCommand normalized = normalize(command, false);
        String tenantId = actor.tenantId().toString();
        String customerCode = codeGenerator.generateUnique(CrmBusinessCodeRules.CUSTOMER,
                candidate -> !store.existsByCode(tenantId, candidate));
        InternalCustomerDetailView created = store.create(
                tenantId, customerCode, normalized, actor.principalId().toString());
        log.info("CRM自研客户创建完成 tenantId={} customerId={} customerCode={} customerName={} actorId={}",
                tenantId, created.id(), created.customerCode(), created.customerName(), actor.principalId());
        return created;
    }

    public InternalCustomerDetailView update(Long id, InternalCustomerCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        InternalCustomerCommand normalized = normalize(command, true);
        String tenantId = actor.tenantId().toString();
        InternalCustomerDetailView updated = store.update(
                tenantId, requireId(id), normalized, actor.principalId().toString());
        log.info("CRM自研客户修改完成 tenantId={} customerId={} customerCode={} revision={} actorId={}",
                tenantId, updated.id(), updated.customerCode(), updated.revision(), actor.principalId());
        return updated;
    }

    public void delete(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        if (revision < 1) throw badRequest("revision必须大于0");
        String tenantId = actor.tenantId().toString();
        store.delete(tenantId, requireId(id), revision, actor.principalId().toString());
        log.info("CRM自研客户逻辑删除完成 tenantId={} customerId={} revision={} actorId={}",
                tenantId, id, revision, actor.principalId());
    }

    public ExternalCrmCustomerSyncResult syncExternalCustomers(ExternalCrmCustomerSyncCommand command) {
        CallerIdentity actor = serviceActor(SYNC_PERMISSION);
        if (command == null) throw badRequest("客户同步参数不能为空");
        String sourceSystem = sourceSystem(command.sourceSystem());
        List<ExternalCrmCustomerRowCommand> rows = externalRows(command.rows());
        ExternalCrmCustomerSyncResult result = store.syncExternalCustomers(
                actor.tenantId().toString(), sourceSystem, rows, syncAuditActor(actor), codeGenerator);
        log.info("CRM外部客户同步完成 tenantId={} sourceSystem={} received={} created={} updated={} unchanged={} failed={}",
                actor.tenantId(), sourceSystem, result.received(), result.created(),
                result.updated(), result.unchanged(), result.failed());
        return result;
    }

    private InternalCustomerCommand normalize(InternalCustomerCommand command, boolean update) {
        if (command == null) throw badRequest("客户参数不能为空");
        Integer revision = command.revision();
        if (update && (revision == null || revision < 1)) throw badRequest("revision必须大于0");
        if (!update && revision != null && revision != 0) throw badRequest("新增客户时revision必须为空或0");
        return new InternalCustomerCommand(
                required(command.customerName(), "customerName不能为空", 200),
                text(command.contactName(), 100, "contactName"),
                text(command.contactPhone(), 50, "contactPhone"),
                code(command.customerTypeCode(), "customerTypeCode", false),
                code(command.regionCode(), "regionCode", false),
                text(command.ownerSalesUserId(), 64, "ownerSalesUserId"),
                text(command.ownerSalesName(), 100, "ownerSalesName"),
                text(command.ownerEmployeeCode(), 50, "ownerEmployeeCode"),
                text(first(command.ownerEmployeeNameSnapshot(), command.ownerSalesName()), 100,
                        "ownerEmployeeNameSnapshot"),
                code(command.settlementTypeCode(), "settlementTypeCode", false),
                text(command.address(), 1000, "address"),
                customerStatus(command.statusCode(), true),
                text(command.remark(), 1000, "remark"),
                update ? revision : 0);
    }

    private static CallerIdentity actor(String permission) {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(permission);
        return caller;
    }

    private static CallerIdentity serviceActor(String permission) {
        CallerIdentity caller = actor(permission);
        if (!"SERVICE".equals(caller.principalScope())) {
            throw new AuthorizationDeniedException("service-caller");
        }
        return caller;
    }

    private static String syncAuditActor(CallerIdentity caller) {
        return "SERVICE".equals(caller.principalScope()) ? SYSTEM_ACTOR : caller.principalId().toString();
    }

    private static String tenant(String permission) {
        return actor(permission).tenantId().toString();
    }

    private static Long requireId(Long id) {
        if (id == null || id < 1) throw badRequest("客户ID无效");
        return id;
    }

    private static int pageBegin(int value) {
        if (value < 0) throw badRequest("begin必须大于等于0");
        return value;
    }

    private static int pageStep(int value) {
        if (value < 1 || value > 200) throw badRequest("step必须在1到200之间");
        return value;
    }

    private static String customerStatus(String value, boolean useDefault) {
        String normalized = code(value, "statusCode", false);
        if (normalized == null) return useDefault ? CrmCustomerStatus.ACTIVE.code() : null;
        if (!CrmCustomerStatus.supports(normalized)) throw badRequest("statusCode无效");
        return normalized;
    }

    private static String code(String value, String name, boolean required) {
        String normalized = upper(value);
        if (normalized == null) {
            if (required) throw badRequest(name + "不能为空");
            return null;
        }
        if (!CODE.matcher(normalized).matches()) throw badRequest(name + "格式无效");
        return normalized;
    }

    private static String required(String value, String message, int max) {
        String normalized = text(value, max, message.replace("不能为空", ""));
        if (normalized == null) throw badRequest(message);
        return normalized;
    }

    private static String text(String value, int max, String name) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > max) throw badRequest(name + "长度不能超过" + max);
        return normalized;
    }

    private static String upper(String value) {
        String normalized = text(value, 64, "code");
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static String sourceSystem(String value) {
        String normalized = text(value, 32, "sourceSystem");
        if (normalized == null) throw badRequest("sourceSystem不能为空");
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!SOURCE_SYSTEM.matcher(normalized).matches()) throw badRequest("sourceSystem格式无效");
        return normalized;
    }

    private static List<ExternalCrmCustomerRowCommand> externalRows(List<ExternalCrmCustomerRowCommand> rows) {
        List<ExternalCrmCustomerRowCommand> source = rows == null ? List.of() : rows;
        if (source.size() > MAX_SYNC_ROWS) throw badRequest("客户同步单次不能超过" + MAX_SYNC_ROWS + "行");
        return source.stream().map(CrmInternalCustomerService::externalRow).toList();
    }

    private static ExternalCrmCustomerRowCommand externalRow(ExternalCrmCustomerRowCommand row) {
        if (row == null) throw badRequest("客户同步行不能为空");
        return new ExternalCrmCustomerRowCommand(
                row.connectorId(),
                text(row.sourceTenantKey(), 128, "sourceTenantKey"),
                required(row.sourceCustomerId(), "sourceCustomerId不能为空", 128),
                text(row.sourceDocumentNo(), 128, "sourceDocumentNo"),
                required(row.customerName(), "customerName不能为空", 200),
                text(row.contactName(), 100, "contactName"),
                text(row.contactPhone(), 50, "contactPhone"),
                text(row.customerSourceName(), 120, "customerSourceName"),
                text(row.businessCategoryName(), 120, "businessCategoryName"),
                text(row.regionName(), 80, "regionName"),
                text(row.cityName(), 80, "cityName"),
                text(row.address(), 1000, "address"),
                text(row.ownerEmployeeCode(), 50, "ownerEmployeeCode"),
                text(row.ownerEmployeeNameSnapshot(), 100, "ownerEmployeeNameSnapshot"),
                code(row.settlementTypeCode(), "settlementTypeCode", false),
                text(row.statusName(), 80, "statusName"),
                row.sourceCreatedAt(),
                row.sourceUpdatedAt(),
                text(row.sourcePayloadHash(), 64, "sourcePayloadHash"),
                text(row.sourcePayloadJson(), 20_000, "sourcePayloadJson"));
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    @SafeVarargs
    private static <T> T first(T... values) {
        for (T value : values) {
            if (value instanceof String text && text.isBlank()) continue;
            if (value != null) return value;
        }
        return null;
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}
