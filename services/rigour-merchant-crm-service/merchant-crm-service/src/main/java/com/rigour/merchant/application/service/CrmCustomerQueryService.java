package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.model.CustomerDetailView;
import com.rigour.merchant.api.v1.model.CustomerSummaryView;
import com.rigour.merchant.api.v1.model.CrmCustomerAreaCommand;
import com.rigour.merchant.api.v1.model.DictionaryView;
import com.rigour.merchant.api.v1.model.ExternalCrmAreaRowCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmAreaSyncCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmAreaSyncResult;
import com.rigour.merchant.api.v1.model.PageView;
import com.rigour.merchant.api.v1.model.ShippingAddressSummaryView;
import com.rigour.merchant.application.port.out.CrmCustomerQueryStore;
import com.rigour.merchant.domain.code.CrmBusinessCodeRules;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Portal CRM 查询用例；所有页面只读取 CRM 本地表。 */
@Service
public final class CrmCustomerQueryService {
    private static final String READ_PERMISSION = "crm:customer:read";
    private static final String WRITE_PERMISSION = "crm:customer:write";
    private static final String SYNC_PERMISSION = "crm:customer:sync";
    private static final String SYSTEM_ACTOR = "SYSTEM";
    private static final int MAX_AREA_SYNC_ROWS = 20_000;
    private static final Pattern SOURCE_SYSTEM = Pattern.compile("[A-Z0-9_]{2,32}");

    private final CrmCustomerQueryStore store;
    private final BusinessCodeGenerator codeGenerator;

    @Autowired
    public CrmCustomerQueryService(CrmCustomerQueryStore store) {
        this(store, new BusinessCodeGenerator());
    }

    CrmCustomerQueryService(CrmCustomerQueryStore store, BusinessCodeGenerator codeGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public PageView<CustomerSummaryView> customers(int begin, int step,
                                                   String query, String status) {
        return store.customers(tenant(), pageBegin(begin), pageStep(step), query, status);
    }

    public CustomerDetailView customer(UUID id) {
        if (id == null) throw new IllegalArgumentException("客户ID不能为空");
        return store.customer(tenant(), id);
    }

    public PageView<ShippingAddressSummaryView> shippingAddresses(
            int begin, int step, String query) {
        return store.shippingAddresses(tenant(), pageBegin(begin), pageStep(step), query);
    }

    public PageView<DictionaryView> customerTypes(int begin, int step, String query) {
        return store.customerTypes(tenant(), pageBegin(begin), pageStep(step), query);
    }

    public PageView<DictionaryView> customerAreas(int begin, int step, String query) {
        return store.customerAreas(tenant(), pageBegin(begin), pageStep(step), query);
    }

    public DictionaryView createCustomerArea(CrmCustomerAreaCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        CrmCustomerAreaCommand normalized = normalizeArea(command, false);
        UUID tenantId = actor.tenantId();
        String areaCode = codeGenerator.generateUnique(CrmBusinessCodeRules.CUSTOMER_AREA,
                candidate -> !store.existsByCustomerAreaCode(tenantId, candidate));
        return store.createCustomerArea(tenantId, areaCode, normalized, actor.principalId());
    }

    public DictionaryView updateCustomerArea(UUID id, CrmCustomerAreaCommand command) {
        if (id == null) throw new IllegalArgumentException("地区ID不能为空");
        CallerIdentity actor = actor(WRITE_PERMISSION);
        return store.updateCustomerArea(actor.tenantId(), id, normalizeArea(command, true), actor.principalId());
    }

    public void deleteCustomerArea(UUID id, int revision) {
        if (id == null) throw new IllegalArgumentException("地区ID不能为空");
        if (revision < 1) throw new IllegalArgumentException("revision必须大于0");
        CallerIdentity actor = actor(WRITE_PERMISSION);
        store.deleteCustomerArea(actor.tenantId(), id, revision, actor.principalId());
    }

    public ExternalCrmAreaSyncResult syncExternalCustomerAreas(ExternalCrmAreaSyncCommand command) {
        CallerIdentity actor = serviceActor(SYNC_PERMISSION);
        if (command == null) throw badRequest("地区同步参数不能为空");
        String sourceSystem = sourceSystem(command.sourceSystem());
        List<ExternalCrmAreaRowCommand> rows = externalAreaRows(command.rows());
        ExternalCrmAreaSyncResult result = store.syncExternalCustomerAreas(
                actor.tenantId(), sourceSystem, rows, syncAuditActor(actor), codeGenerator);
        return result;
    }

    private static UUID tenant() {
        return actor(READ_PERMISSION).tenantId();
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

    private static CrmCustomerAreaCommand normalizeArea(CrmCustomerAreaCommand command, boolean update) {
        if (command == null) throw new IllegalArgumentException("地区参数不能为空");
        Integer revision = command.revision();
        if (update && (revision == null || revision < 1)) throw new IllegalArgumentException("revision必须大于0");
        if (!update && revision != null && revision != 0) {
            throw new IllegalArgumentException("新增地区时revision必须为空或0");
        }
        return new CrmCustomerAreaCommand(
                required(command.areaName(), "地区名称不能为空", 160),
                code(command.parentAreaCode(), "parentAreaCode"),
                status(command.status()),
                update ? revision : 0);
    }

    private static String required(String value, String message, int max) {
        String text = text(value, max);
        if (text == null) throw new IllegalArgumentException(message);
        return text;
    }

    private static String code(String value, String name) {
        String text = text(value, 128);
        if (text == null) return null;
        String upper = text.toUpperCase(Locale.ROOT);
        if (!upper.matches("[A-Z0-9][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException(name + "格式无效");
        }
        return upper;
    }

    private static String status(String value) {
        String text = text(value, 24);
        if (text == null) return "ACTIVE";
        String upper = text.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "ACTIVE", "INACTIVE" -> upper;
            default -> throw new IllegalArgumentException("地区状态无效");
        };
    }

    private static String sourceSystem(String value) {
        String text = text(value, 32);
        if (text == null) throw badRequest("sourceSystem不能为空");
        String upper = text.toUpperCase(Locale.ROOT);
        if (!SOURCE_SYSTEM.matcher(upper).matches()) throw badRequest("sourceSystem格式无效");
        return upper;
    }

    private static List<ExternalCrmAreaRowCommand> externalAreaRows(List<ExternalCrmAreaRowCommand> rows) {
        List<ExternalCrmAreaRowCommand> source = rows == null ? List.of() : rows;
        if (source.size() > MAX_AREA_SYNC_ROWS) throw badRequest("地区同步单次不能超过" + MAX_AREA_SYNC_ROWS + "行");
        return source.stream().map(CrmCustomerQueryService::externalAreaRow).toList();
    }

    private static ExternalCrmAreaRowCommand externalAreaRow(ExternalCrmAreaRowCommand row) {
        if (row == null) throw badRequest("地区同步行不能为空");
        String regionName = text(row.regionName(), 80);
        String cityName = text(row.cityName(), 80);
        if (regionName == null && cityName == null) throw badRequest("地区同步行缺少区域或城市");
        return new ExternalCrmAreaRowCommand(
                row.connectorId(),
                text(row.sourceTenantKey(), 128),
                text(row.sourceAreaId(), 128),
                regionName,
                cityName,
                row.sourceCreatedAt(),
                row.sourceUpdatedAt());
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String text = value.strip();
        if (text.length() > max) throw new IllegalArgumentException("参数长度不能超过" + max);
        return text;
    }

    private static int pageBegin(int value) {
        if (value < 0) throw new IllegalArgumentException("begin必须大于等于0");
        return value;
    }

    private static int pageStep(int value) {
        if (value < 1 || value > 200) throw new IllegalArgumentException("step必须在1到200之间");
        return value;
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
