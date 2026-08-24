package com.rigour.merchant.application.service;

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
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

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
                                                           String ownerStaffCode,
                                                           String statusCode) {
        String tenantId = tenant(READ_PERMISSION);
        CustomerSearchCriteria criteria = new CustomerSearchCriteria(
                text(customerCode, 50, "customerCode"),
                text(customerName, 200, "customerName"),
                text(contactPhone, 50, "contactPhone"),
                code(customerTypeCode, "customerTypeCode", false),
                code(regionCode, "regionCode", false),
                text(ownerSalesUserId, 64, "ownerSalesUserId"),
                text(ownerStaffCode, 50, "ownerStaffCode"),
                customerStatus(statusCode, false));
        PageView<InternalCustomerSummaryView> result = store.customers(
                tenantId, pageBegin(begin), pageStep(step), criteria);
        log.debug("CRM自研客户列表查询完成 tenantId={} customerCode={} customerName={} contactPhone={} customerTypeCode={} regionCode={} ownerSalesUserId={} ownerStaffCode={} statusCode={} count={} total={}",
                tenantId, value(criteria.customerCode()), value(criteria.customerName()),
                value(criteria.contactPhone()), value(criteria.customerTypeCode()), value(criteria.regionCode()),
                value(criteria.ownerSalesUserId()), value(criteria.ownerStaffCode()), value(criteria.statusCode()),
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
                text(command.ownerStaffCode(), 50, "ownerStaffCode"),
                text(first(command.ownerStaffNameSnapshot(), command.ownerSalesName()), 100,
                        "ownerStaffNameSnapshot"),
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
