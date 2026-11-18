package com.rigour.order.application.service.cost;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.application.port.out.*;
import com.rigour.order.domain.cost.LogisticsLedgerRules;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public final class OrderLogisticsLedgerService {
    private final OrderLogisticsReferences references;
    private final OrderLedgerSourceStore sales;

    public OrderLogisticsLedgerService(
            OrderLogisticsReferences references, OrderLedgerSourceStore sales) {
        this.references = references;
        this.sales = sales;
    }

    public List<LogisticsLedgerPartyView> parties(
            String kind,
            String code,
            Long id,
            String keyword,
            String city,
            int begin,
            String action) {
        if (!Set.of("WAREHOUSE", "SUPPLIER", "CUSTOMER").contains(Objects.toString(kind, ""))
                || begin < 0
                || begin > 100000) throw bad("收发方查询参数无效");
        if ("CUSTOMER".equals(kind)) references.city(city, action);
        return references.ledgerParties(kind, code, id, keyword, city, begin, action);
    }

    public List<LogisticsCostSourceView> sources(LogisticsLedgerSourceQuery q, String action) {
        if (q == null
                || q.employeeName() != null && q.employeeName().length() > 100
                || q.departmentName() != null && q.departmentName().length() > 100
                || q.historical()
                        && (!q.exact() || q.documentNo() == null || q.documentNo().isBlank())
                || q.begin() < 0
                || q.begin() > 100000
                || q.documentNo() != null && q.documentNo().length() > 100
                || q.partyName() != null && q.partyName().length() > 200
                || q.dateFrom() != null && q.dateTo() != null && q.dateTo().isBefore(q.dateFrom()))
            throw bad("单据筛选条件无效");
        if (!"SALES_ORDER".equals(q.orderTypeCode()))
            return withUsage(references.ledgerSources(q, action), q, action);
        Long warehouseId = null;
        if (q.sourceWarehouseCode() != null && !q.sourceWarehouseCode().isBlank())
            warehouseId =
                    Long.valueOf(party("WAREHOUSE", q.sourceWarehouseCode(), null, action).id());
        if (q.targetWarehouseCode() != null && !q.targetWarehouseCode().isBlank()) return List.of();
        boolean departmentFilter = q.departmentName() != null && !q.departmentName().isBlank();
        var employees =
                departmentFilter
                        ? employeeDirectory(action)
                        : Map.<String, LogisticsCostEmployeeView>of();
        var departmentCodes = new LinkedHashSet<String>();
        employees.forEach(
                (code, employee) -> {
                    if (employee.departmentName() != null
                            && employee.departmentName().contains(q.departmentName()))
                        departmentCodes.add(code);
                });
        String tenant = AuthorizationContext.requireCurrent().tenantId().toString();
        var documents =
                departmentFilter
                        ? sales.sales(tenant, q, action, warehouseId, departmentCodes)
                        : sales.sales(tenant, q, action, warehouseId);
        return withUsage(
                salesViews(documents, action, departmentFilter ? employees : null), q, action);
    }

    public List<LogisticsCostSourceView> salesSources(
            List<String> nos, String action, boolean historical) {
        if (nos == null
                || nos.size() > 100
                || new HashSet<>(nos).size() != nos.size()
                || nos.stream().anyMatch(n -> n == null || n.isBlank() || n.length() > 100))
            throw bad("销售单据数量或编号无效");
        if (nos.isEmpty()) return List.of();
        var documents =
                sales.salesByNos(
                        AuthorizationContext.requireCurrent().tenantId().toString(),
                        nos,
                        action,
                        historical);
        return salesViews(documents, action, null, !historical);
    }

    private List<LogisticsCostSourceView> salesViews(
            List<OrderDeliveryCostSourceView> documents, String action) {
        return salesViews(documents, action, null);
    }

    private Map<String, LogisticsCostEmployeeView> employeeDirectory(String action) {
        // 现有HR费用员工目录仅接受租户用户；自动同步继续保留原单归属，不能伪造用户身份取资料。
        if (!"TENANT".equals(AuthorizationContext.requireCurrent().principalScope()))
            return Map.of();
        Map<String, LogisticsCostEmployeeView> result = new HashMap<>();
        Set<String> conflicts = new HashSet<>();
        for (var employee : references.employees(action)) {
            if (employee.employeeCode() == null || employee.employeeCode().isBlank()) continue;
            var previous = result.putIfAbsent(employee.employeeCode(), employee);
            if (previous != null && !previous.equals(employee))
                conflicts.add(employee.employeeCode());
        }
        conflicts.forEach(result::remove);
        return result;
    }

    private List<LogisticsCostSourceView> salesViews(
            List<OrderDeliveryCostSourceView> documents,
            String action,
            Map<String, LogisticsCostEmployeeView> directory) {
        return salesViews(documents, action, directory, true);
    }

    private List<LogisticsCostSourceView> salesViews(
            List<OrderDeliveryCostSourceView> documents, String action,
            Map<String, LogisticsCostEmployeeView> directory, boolean validateCity) {
        List<LogisticsCostSourceView> result = new ArrayList<>();
        boolean needsDepartment =
                documents.stream()
                        .anyMatch(
                                s ->
                                        s.employeeCode() != null
                                                && !s.employeeCode().isBlank()
                                                && (s.departmentName() == null
                                                        || s.departmentName().isBlank()));
        var employees =
                directory != null
                        ? directory
                        : needsDepartment
                                ? employeeDirectory(action)
                                : Map.<String, LogisticsCostEmployeeView>of();
        var operatingCities =
                documents.isEmpty() || !validateCity ? Map.<String, String>of() : references.cities(action);
        Map<Long, LogisticsLedgerPartyView> warehouses = new HashMap<>();
        Map<String, Map<String, LogisticsLedgerPartyView>> customers = new HashMap<>();
        Map<String, Set<String>> byCity = new LinkedHashMap<>();
        for (var source : documents)
            if (source.cityCode() != null
                    && (!validateCity || operatingCities.containsKey(source.cityCode()))
                    && source.customerCode() != null
                    && (source.contactName() == null
                            || source.contactPhone() == null
                            || source.destinationAddress() == null))
                byCity.computeIfAbsent(source.cityCode(), key -> new LinkedHashSet<>())
                        .add(source.customerCode());
        var requests = new LinkedHashMap<String, List<String>>();
        byCity.forEach((city, codes) -> requests.put(city, new ArrayList<>(codes)));
        var batched =
                requests.isEmpty()
                        ? Map.<String, List<LogisticsLedgerPartyView>>of()
                        : references.ledgerCustomersByCities(requests, action);
        batched.forEach(
                (city, rows) -> {
                    Map<String, LogisticsLedgerPartyView> indexed = new HashMap<>();
                    rows.forEach(row -> indexed.put(row.code(), row));
                    customers.put(city, indexed);
                });
        for (var s : documents) {
            var employee = s.employeeCode() == null ? null : employees.get(s.employeeCode());
            Long departmentId = s.departmentId();
            String departmentName = s.departmentName();
            // 只有缺失的归属才按稳定员工编码补齐，已有历史归属不随员工调岗变化。
            if ((departmentName == null || departmentName.isBlank())
                    && employee != null
                    && (departmentId == null || departmentId.equals(employee.departmentId()))) {
                departmentId = employee.departmentId();
                departmentName = employee.departmentName();
            }
            LogisticsLedgerPartyView warehouse =
                    s.warehouseId() == null
                            ? null
                            : warehouses.computeIfAbsent(
                                    s.warehouseId(),
                                    id ->
                                            references
                                                    .ledgerParties(
                                                            "WAREHOUSE",
                                                            null,
                                                            id,
                                                            null,
                                                            null,
                                                            0,
                                                            action)
                                                    .stream()
                                                    .findFirst()
                                                    .orElseThrow(() -> bad("来源发货仓库无权限")));
            var customer =
                    s.cityCode() == null || s.customerCode() == null
                            ? null
                            : customers.getOrDefault(s.cityCode(), Map.of()).get(s.customerCode());
            String issue =
                    s.cityCode() == null || validateCity && !operatingCities.containsKey(s.cityCode())
                            ? "销售订单归属城市未配置或不在可用范围，请先维护原单归属"
                            : null;
            String contactName =
                    first(s.contactName(), customer == null ? null : customer.contactName());
            String contactPhone =
                    first(s.contactPhone(), customer == null ? null : customer.phone());
            String address =
                    first(s.destinationAddress(), customer == null ? null : customer.address());
            if (issue == null
                    && (s.customerCode() == null
                            || contactName == null
                            || contactPhone == null
                            || address == null)) issue = "客户收货资料不完整，请先维护客户档案的收货人、电话及地址";
            result.add(
                    new LogisticsCostSourceView(
                                    s.id(),
                                    s.documentNo(),
                                    "SALES_ORDER",
                                    s.cityCode(),
                                    s.warehouseId(),
                                    null,
                                    warehouse == null ? null : warehouse.code(),
                                    warehouse == null ? null : warehouse.name(),
                                    s.customerCode(),
                                    s.customerName(),
                                    s.statusCode(),
                                    s.sourceSystem(),
                                    s.originalNo(),
                                    s.documentDate(),
                                    warehouse == null ? null : warehouse.address(),
                                    address,
                                    null,
                                    null,
                                    s.employeeCode(),
                                    s.employeeName(),
                                    departmentId,
                                    departmentName,
                                    contactName,
                                    contactPhone,
                                    warehouse == null ? null : warehouse.cityCode(),
                                    issue)
                            .withBusinessSnapshot(s.documentAmount(), null));
        }
        return result;
    }

    private List<LogisticsCostSourceView> withUsage(
            List<LogisticsCostSourceView> rows, LogisticsLedgerSourceQuery query, String action) {
        if (query.exact() || query.historical() || rows.isEmpty()) return rows;
        var counts =
                sales.logisticsUsage(
                        AuthorizationContext.requireCurrent().tenantId().toString(),
                        rows.stream().map(LogisticsCostSourceView::documentNo).toList(),
                        action);
        return rows.stream()
                .map(row -> row.withLinkedLogisticsCount(counts.getOrDefault(row.documentNo(), 0)))
                .toList();
    }

    private LogisticsLedgerPartyView party(String kind, String code, String city, String action) {
        return parties(kind, code, null, null, city, 0, action).stream()
                .filter(p -> Objects.equals(code, p.code()))
                .findFirst()
                .orElseThrow(() -> bad("收发方不存在、已停用或无权限：" + code));
    }

    public LogisticsCostCommand normalize(LogisticsCostCommand c, String action, boolean confirm) {
        var ledger = c.ledger();
        if ("NON_SALES".equals(ledger.transportPurposeCode()))
            ledger = ledger.withTransportType("OTHER");
        if (confirm && ledger.sourceSync() != null && ledger.sourceSync().differencePending())
            throw bad("采购来源存在未处理差异，请编辑核实后确认");
        java.math.BigDecimal amount;
        try {
            amount = LogisticsLedgerRules.validate(ledger, confirm);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        Set<Long> warehouses = new LinkedHashSet<>();
        Set<String> cities = new LinkedHashSet<>();
        cities.add(c.cityCode());
        String senderCity =
                ledger.sender().cityCode() == null || ledger.sender().cityCode().isBlank()
                        ? c.cityCode()
                        : ledger.sender().cityCode();
        references.city(senderCity, action);
        cities.add(senderCity);
        var sender = resolve(ledger.sender(), senderCity, action, warehouses);
        if (sender.cityCode() != null && !sender.cityCode().isBlank()) {
            references.city(sender.cityCode(), action);
            cities.add(sender.cityCode());
        }
        List<LogisticsCostLedger.Party> points = new ArrayList<>();
        for (var p : ledger.receivingPoints()) {
            references.city(p.cityCode(), action);
            cities.add(p.cityCode());
            points.add(resolve(p, p.cityCode(), action, warehouses));
        }
        Map<String, String> pointCities = new HashMap<>();
        points.forEach(p -> pointCities.put(p.pointCode(), p.cityCode()));
        var attributed = new ArrayList<LogisticsCostLedger.Source>();
        Map<Boolean, Map<String, LogisticsCostSourceView>> salesByUse = new HashMap<>();
        for (boolean historical : List.of(false, true)) {
            var codes =
                    ledger.sources().stream()
                            .filter(
                                    s ->
                                            "SALES_ORDER".equals(s.orderTypeCode())
                                                    && historical
                                                            == "TRACE".equals(s.linkRoleCode()))
                            .map(LogisticsCostLedger.Source::orderCode)
                            .distinct()
                            .toList();
            if (!codes.isEmpty()) {
                Map<String, LogisticsCostSourceView> indexed = new HashMap<>();
                salesSources(codes, action, historical)
                        .forEach(s -> indexed.put(s.documentNo(), s));
                salesByUse.put(historical, indexed);
            }
        }
        for (var link : ledger.sources()) {
            var normalizedLink =
                    new LogisticsCostLedger.Source(
                            link.orderTypeCode(),
                            link.orderCode(),
                            link.linkRoleCode(),
                            link.allocations(),
                            link.noSourceReason());
            if ("COST".equals(link.linkRoleCode())
                    && "DELIVERY".equals(ledger.transportPurposeCode())
                    && !"SALES_ORDER".equals(link.orderTypeCode())) throw bad("销售订单配送必须关联销售单");
            if ("COST".equals(link.linkRoleCode())
                    && "TRANSFER".equals(ledger.transportPurposeCode())
                    && !"TRANSFER_ORDER".equals(link.orderTypeCode())) throw bad("仓间调拨必须关联调拨单");
            if (!"NONE".equals(link.orderTypeCode())) {
                if ("REQUISITION_OUT".equals(link.orderTypeCode()))
                    throw bad("领用等其他订单请关联真实其他出库单，并选择其他费用");
                if ("PURCHASE_ORDER".equals(link.orderTypeCode())
                        && "COST".equals(link.linkRoleCode()))
                    throw bad("采购运费归入采购成本；采购单在物流记录中仅可用于历史业务追溯");
                var q =
                        new LogisticsLedgerSourceQuery(
                                link.orderTypeCode(),
                                link.orderCode(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                0,
                                true,
                                "TRACE".equals(link.linkRoleCode()));
                var candidates =
                        "SALES_ORDER".equals(link.orderTypeCode())
                                ? salesByUse
                                        .getOrDefault("TRACE".equals(link.linkRoleCode()), Map.of())
                                        .values()
                                : sources(q, action);
                var source =
                        candidates.stream()
                                .filter(s -> Objects.equals(s.documentNo(), link.orderCode()))
                                .findFirst()
                                .orElseThrow(() -> bad("来源单据不存在、状态不可引用或无权限：" + link.orderCode()));
                normalizedLink = normalizedLink.attributed(source);
                if (confirm
                        && "COST".equals(link.linkRoleCode())
                        && source.referenceIssue() != null
                        && !"DELIVERY".equals(ledger.transportPurposeCode()))
                    throw bad(source.referenceIssue());
                if ("DELIVERY".equals(ledger.transportPurposeCode())
                        && "COST".equals(link.linkRoleCode())) {
                    if (source.receiverCode() == null) throw bad("来源销售单缺少客户编号，请维护原单");
                    for (var allocation : link.allocations()) {
                        int index = -1;
                        for (int i = 0; i < points.size(); i++)
                            if (Objects.equals(
                                    points.get(i).pointCode(), allocation.receivingPointCode())) {
                                index = i;
                                break;
                            }
                        if (index < 0
                                || !Objects.equals(
                                        points.get(index).partyCode(), source.receiverCode()))
                            throw bad("收货方必须与所关联销售单的客户一致");
                        var oldPoint = points.get(index);
                        if (!Objects.equals(oldPoint.cityCode(), source.cityCode()))
                            throw bad("收货城市必须与所关联销售单一致");
                        if (confirm
                                && (first(oldPoint.contactName(), source.contactName()) == null
                                        || first(oldPoint.phone(), source.contactPhone()) == null
                                        || first(oldPoint.address(), source.destinationAddress())
                                                == null)) throw bad("请补齐实际收货联系人、联系电话及地址");
                        points.set(
                                index,
                                new LogisticsCostLedger.Party(
                                        oldPoint.pointCode(),
                                        "CUSTOMER",
                                        source.receiverCode(),
                                        oldPoint.manualName(),
                                        first(oldPoint.manualName(), source.receiverName()),
                                        first(oldPoint.contactName(), source.contactName()),
                                        first(oldPoint.phone(), source.contactPhone()),
                                        first(oldPoint.address(), source.destinationAddress()),
                                        source.cityCode()));
                    }
                }
                if (source.cityCode() != null) {
                    references.city(source.cityCode(), action);
                    cities.add(source.cityCode());
                }
                if (source.warehouseId() != null) warehouses.add(source.warehouseId());
                if (source.targetWarehouseId() != null) warehouses.add(source.targetWarehouseId());
            }
            attributed.add(normalizedLink);
            if (link.allocations() != null)
                for (var allocation : link.allocations()) {
                    references.city(allocation.cityCode(), action);
                    if (!Objects.equals(
                            pointCities.get(allocation.receivingPointCode()),
                            allocation.cityCode())) throw bad("费用归属城市须与实际收货点城市一致");
                }
        }
        if ("DELIVERY".equals(ledger.transportPurposeCode())) {
            Set<String> linkedPoints = new HashSet<>();
            attributed.stream()
                    .filter(s -> "COST".equals(s.linkRoleCode()))
                    .forEach(
                            s ->
                                    s.allocations()
                                            .forEach(
                                                    a -> linkedPoints.add(a.receivingPointCode())));
            if (points.stream().anyMatch(p -> !linkedPoints.contains(p.pointCode())))
                throw bad("销售配送的每个收货方均须来自所关联销售单");
        }
        if (!"SELF".equals(ledger.deliveryModeCode())
                && !references
                        .validCodes(
                                AuthorizationContext.requireCurrent().tenantId().toString(),
                                "COST_CARRIER")
                        .contains(ledger.carrierCode())) throw bad("请选择启用的物流公司");
        String tenant = AuthorizationContext.requireCurrent().tenantId().toString();
        for (var fee : ledger.feeItems()) {
            if (!references
                    .validCodes(tenant, "COST_LOGISTICS_FEE_TYPE")
                    .contains(fee.feeTypeCode())) throw bad("请选择启用的物流费用项目");
            if ("QUANTITY".equals(fee.allocationMethodCode())
                    && !references.validCodes(tenant, "PRODUCT_UNIT").contains(fee.basisUnitCode()))
                throw bad("请为数量基数选择有效统一单位");
        }
        if (ledger.transportMethodCode() != null
                && !ledger.transportMethodCode().isBlank()
                && !references
                        .validCodes(tenant, "COST_TRANSPORT_KIND")
                        .contains(ledger.transportMethodCode())) throw bad("请选择启用的运输方式");
        return new LogisticsCostCommand(
                c.requestId(),
                c.revision(),
                "物流运输-" + c.costDate(),
                ledger.transportPurposeCode(),
                c.cityCode(),
                c.cityName(),
                c.departmentId(),
                c.ownerEmployeeCode(),
                c.costDate(),
                amount,
                c.paymentStatusCode(),
                null,
                c.remark(),
                c.reason(),
                ledger.withSources(List.copyOf(attributed))
                        .resolved(sender, List.copyOf(points), List.copyOf(warehouses))
                        .withScopeCities(List.copyOf(cities)));
    }

    private LogisticsCostLedger.Party resolve(
            LogisticsCostLedger.Party p, String city, String action, Set<Long> warehouses) {
        if ("OTHER".equals(p.partyTypeCode()))
            return new LogisticsCostLedger.Party(
                    p.pointCode(),
                    p.partyTypeCode(),
                    null,
                    p.manualName(),
                    p.manualName(),
                    p.contactName(),
                    p.phone(),
                    p.address(),
                    p.cityCode());
        var ref = party(p.partyTypeCode(), p.partyCode(), city, action);
        if ("WAREHOUSE".equals(p.partyTypeCode())) {
            warehouses.add(Long.valueOf(ref.id()));
            if (ref.cityCode() == null || ref.cityCode().isBlank())
                throw bad(
                        "仓库“"
                                + ref.name()
                                + "（"
                                + ref.code()
                                + "）”未维护业务归属地区，请到 ERP → 库存管理 → 仓库信息补齐后重新保存；所在地区不能替代业务归属地区");
            references.city(ref.cityCode(), action);
            if (p.cityCode() != null
                    && !p.cityCode().isBlank()
                    && !Objects.equals(ref.cityCode(), p.cityCode()))
                throw bad("仓库“" + ref.name() + "”的业务归属地区与所填运输城市不一致，请核对仓库档案和运输城市");
        }
        return new LogisticsCostLedger.Party(
                p.pointCode(),
                p.partyTypeCode(),
                ref.code(),
                p.manualName(),
                first(p.manualName(), ref.name()),
                p.contactName(),
                p.phone(),
                p.address(),
                "WAREHOUSE".equals(p.partyTypeCode()) ? ref.cityCode() : p.cityCode());
    }

    public LogisticsCostCommand enrich(LogisticsCostCommand c, String action) {
        return enrich(c, action, new HashMap<>());
    }

    public List<LogisticsCostDetailView> enrich(List<LogisticsCostDetailView> rows, String action) {
        // 只在本次查询内复用，避免不同租户、权限或后续档案更名共享旧结果。
        Map<PartyReference, Optional<LogisticsLedgerPartyView>> names = new HashMap<>();
        return rows.stream()
                .map(
                        row ->
                                row.data().ledger() == null
                                        ? row
                                        : row.withData(enrich(row.data(), action, names)))
                .toList();
    }

    private LogisticsCostCommand enrich(
            LogisticsCostCommand c,
            String action,
            Map<PartyReference, Optional<LogisticsLedgerPartyView>> names) {
        if (c.ledger() == null) return c;
        var p = c.ledger();
        var sender =
                enrichParty(
                        p.sender(),
                        p.sender().cityCode() == null || p.sender().cityCode().isBlank()
                                ? c.cityCode()
                                : p.sender().cityCode(),
                        action,
                        names);
        var points =
                p.receivingPoints().stream()
                        .map(point -> enrichParty(point, point.cityCode(), action, names))
                        .toList();
        return c.withLedger(p.resolved(sender, points, p.scopeWarehouseIds()), c.amount());
    }

    private record PartyReference(String kind, String code, String city) {}

    private LogisticsCostLedger.Party enrichParty(
            LogisticsCostLedger.Party p,
            String city,
            String action,
            Map<PartyReference, Optional<LogisticsLedgerPartyView>> names) {
        // 保存时已经核验过档案；读取保留实际运输快照，不逐点串行回查当前档案。
        if ("OTHER".equals(p.partyTypeCode())
                || p.displayName() != null && !p.displayName().isBlank()) return p;
        // 来源物流尚未关联本系统客户时可能没有城市或本地客户编码。
        // 不能拿来源编码发起无范围的客户查询；先保留真实收发货快照供后续补齐。
        if ("CUSTOMER".equals(p.partyTypeCode())
                && (city == null || city.isBlank() || p.partyCode() == null || p.partyCode().isBlank()))
            return p;
        if (!Set.of("WAREHOUSE", "SUPPLIER", "CUSTOMER")
                .contains(Objects.toString(p.partyTypeCode(), ""))) throw bad("收发方查询参数无效");
        // 已持久化费用经过列表数据范围校验；CRM 客户引用还会按 action 和 city 校验权限。
        // 补名称无需再为每个客户读取整份 CRM → HR 城市目录；新增、编辑及确认仍走 parties/normalize。
        var ref =
                names.computeIfAbsent(
                        new PartyReference(p.partyTypeCode(), p.partyCode(), city),
                        key ->
                                references
                                        .ledgerParties(
                                                key.kind(),
                                                key.code(),
                                                null,
                                                null,
                                                key.city(),
                                                0,
                                                action)
                                        .stream()
                                        .filter(r -> Objects.equals(r.code(), key.code()))
                                        .findFirst());
        return new LogisticsCostLedger.Party(
                p.pointCode(),
                p.partyTypeCode(),
                p.partyCode(),
                null,
                ref.map(LogisticsLedgerPartyView::name).orElse(p.partyCode()),
                p.contactName(),
                p.phone(),
                p.address(),
                p.cityCode());
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
