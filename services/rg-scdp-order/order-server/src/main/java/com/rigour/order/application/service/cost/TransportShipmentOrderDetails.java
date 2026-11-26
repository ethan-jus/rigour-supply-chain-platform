package com.rigour.order.application.service.cost;

import com.rigour.order.api.v1.model.LogisticsCostEmployeeView;
import com.rigour.order.api.v1.model.ShipmentTransportModels.SalesOrderDetails;
import com.rigour.order.api.v1.model.ShipmentTransportModels.Source;
import com.rigour.order.application.port.out.DhbCachedOrderFactsClient;
import com.rigour.order.application.port.out.OrderLedgerSourceStore;
import com.rigour.order.application.port.out.OrderLogisticsReferences;
import com.rigour.order.application.port.out.OrderLogisticsReferences.DepartmentAt;
import com.rigour.order.application.port.out.ShipmentTransportStore;
import com.rigour.shared.context.AuthorizationContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 补充运输详情的业务单资料，原费用台账及分摊数据仍由费用记录提供。 */
@Service
class TransportShipmentOrderDetails {
    private static final String READ = "order:logistics-cost:read";
    private final OrderLedgerSourceStore orders;
    private final DhbCachedOrderFactsClient cachedOrders;
    private final OrderLogisticsReferences references;
    private final ShipmentTransportStore shipments;

    TransportShipmentOrderDetails(
            OrderLedgerSourceStore orders, DhbCachedOrderFactsClient cachedOrders,
            OrderLogisticsReferences references, ShipmentTransportStore shipments) {
        this.orders = orders;
        this.cachedOrders = cachedOrders;
        this.references = references;
        this.shipments = shipments;
    }

    List<Source> enrich(String tenant, String costId, List<Source> sources) {
        if (sources.isEmpty()) return sources;
        var orderNos = sources.stream().map(Source::salesOrderNo).filter(Objects::nonNull).distinct().toList();
        var canonical = new HashMap<String, Facts>();
        if (!orderNos.isEmpty()) {
            for (var order : orders.salesByNos(tenant, orderNos, READ, false))
                canonical.put(order.documentNo(), new Facts(order.customerName(), order.employeeCode(),
                        order.employeeName(), order.departmentId(), order.departmentName(),
                        order.documentDate(), order.documentAmount()));
        }
        Map<UUID, Set<String>> sourceNos = new LinkedHashMap<>();
        for (var source : sources)
            if (source.salesOrderNo() == null && source.sourceSalesOrderNo() != null)
                sourceNos.computeIfAbsent(source.latest().connectorId(), ignored -> new LinkedHashSet<>())
                        .add(source.sourceSalesOrderNo());
        Map<UUID, Map<String, Facts>> sourceFacts = new HashMap<>();
        sourceNos.forEach((connector, numbers) -> {
            Map<String, Facts> facts = new HashMap<>();
            var ordered = List.copyOf(numbers);
            for (int start = 0; start < ordered.size(); start += 100) {
                var batch = ordered.subList(start, Math.min(start + 100, ordered.size()));
                for (var order : cachedOrders.read(tenant, connector, batch))
                    if (batch.contains(order.orderNo()))
                        facts.put(order.orderNo(), new Facts(order.customerName(), order.employeeCode(),
                                order.employeeName(), null, null, order.documentDate(), order.orderAmount()));
            }
            sourceFacts.put(connector, facts);
        });

        var factsBySource = new HashMap<String, Facts>();
        for (var source : sources) {
            var facts = source.salesOrderNo() != null ? canonical.get(source.salesOrderNo())
                    : source.sourceSalesOrderNo() == null ? null
                            : sourceFacts.getOrDefault(source.latest().connectorId(), Map.of())
                                    .get(source.sourceSalesOrderNo());
            if (facts != null) factsBySource.put(source.id(), facts);
        }
        var dates = shipments.repairedShipmentLinkTimes(tenant, costId);
        var lookups = factsBySource.values().stream().map(Facts::missingDepartment)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        var departments = lookups.isEmpty() ? Map.<DepartmentAt, LogisticsCostEmployeeView>of()
                : references.employeeDepartmentsAt(AuthorizationContext.requireCurrent(), lookups, READ);
        return sources.stream().map(source -> {
            var linkTime = dates.get(source.latest().shipmentNo());
            var facts = factsBySource.get(source.id());
            if (facts == null) {
                return linkTime == null || source.salesOrderNo() == null && source.sourceSalesOrderNo() == null
                        ? source : source.withSalesOrderDetails(new SalesOrderDetails(null, null, null, null, null, linkTime));
            }
            String departmentName = facts.departmentName();
            var key = facts.missingDepartment();
            var employee = key == null ? null : departments.get(key);
            if (employee != null && (facts.departmentId() == null || facts.departmentId().equals(employee.departmentId())))
                departmentName = employee.departmentName();
            return source.withSalesOrderDetails(new SalesOrderDetails(
                    facts.customerName(), facts.employeeName(), departmentName,
                    facts.documentDate(), facts.amount(), linkTime));
        }).toList();
    }

    private record Facts(String customerName, String employeeCode, String employeeName,
                         Long departmentId, String departmentName, LocalDate documentDate, BigDecimal amount) {
        DepartmentAt missingDepartment() {
            return employeeCode == null || employeeCode.isBlank() || documentDate == null
                    || departmentName != null && !departmentName.isBlank()
                    ? null : new DepartmentAt(employeeCode, documentDate);
        }
    }
}
