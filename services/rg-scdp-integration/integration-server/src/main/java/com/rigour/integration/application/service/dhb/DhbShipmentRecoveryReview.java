package com.rigour.integration.application.service.dhb;

import com.rigour.integration.application.port.out.*;
import com.rigour.integration.application.port.out.DhbClient.Shipment;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;

/** 自动复核已补齐的明确依赖，只恢复原队列项；不猜客户、不改绑、不直接写运输或库存。 */
@Component
public class DhbShipmentRecoveryReview {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final DhbIncrementalWorkStore work;
    private final DhbIntegrationStore issues;
    private final DhbSyncStore mappings;

    public DhbShipmentRecoveryReview(
            DhbIncrementalWorkStore work, DhbIntegrationStore issues, DhbSyncStore mappings) {
        this.work = work;
        this.issues = issues;
        this.mappings = mappings;
    }

    public int reconsider(UUID tenant, UUID connector, Instant now) {
        var cache = new HashMap<Key, Optional<DhbSyncStore.ExternalObjectMapping>>();
        var blocked = new HashMap<Key, Boolean>();
        int resumed = 0;
        for (var item : work.manualShipments(tenant, connector, 1000, now)) {
            var issue = issues.openException(tenant, connector, "SALES_SHIPMENT", item.id());
            if (issue == null) {
                work.deferShipmentReview(tenant, connector, item, now.plusSeconds(300));
                continue;
            }
            // 单位维护在商品与原销售单明细中；交回原投影重新校验，不代填默认单位。
            if ("DHB_SHIPMENT_LINE_UNIT_MISSING".equals(issue.lastErrorCode())) {
                if (work.resumeShipment(tenant, connector, item, now)) resumed++;
                continue;
            }
            var evidence =
                    issues.openReconciliation(tenant, issue.runId(), "SALES_SHIPMENT", item.id());
            var needed =
                    evidence == null
                            ? null
                            : requirement(
                                    issue.lastErrorCode(),
                                    evidence.expectedValueJson(),
                                    evidence.actualValueJson(),
                                    item.payload());
            boolean ready = needed != null;
            DhbSyncStore.ExternalObjectMapping selected = null;
            for (String id : needed == null ? List.<String>of() : needed.ids()) {
                var key = new Key(needed.type(), id);
                if (blocked.computeIfAbsent(
                        key,
                        k ->
                                mappings.dependencyAutoSyncBlocked(
                                        tenant, connector, List.of(k.type()), List.of(k.id())))) {
                    ready = false;
                    break;
                }
                var mapping =
                        cache.computeIfAbsent(
                                        key,
                                        k ->
                                                Optional.ofNullable(
                                                        mappings.findActiveMapping(
                                                                tenant, connector, k.type(),
                                                                k.id())))
                                .orElse(null);
                if (mapping == null || mapping.internalObjectId() == null) continue;
                if (selected != null
                        && (!Objects.equals(selected.internalObjectId(), mapping.internalObjectId())
                                || !Objects.equals(
                                        selected.internalDomain(), mapping.internalDomain())
                                || !Objects.equals(
                                        selected.internalObjectType(),
                                        mapping.internalObjectType()))) {
                    ready = false;
                    break;
                }
                selected = mapping;
            }
            if (ready && selected != null && work.resumeShipment(tenant, connector, item, now))
                resumed++;
            else work.deferShipmentReview(tenant, connector, item, now.plusSeconds(300));
        }
        return resumed;
    }

    static Requirement requirement(String code, String expected, String actual, String payload) {
        var normal = DhbDependencyRequirement.from(code, expected, actual);
        if (normal != null) return new Requirement(normal.type(), List.of(normal.sourceId()));
        if (!"DHB_DEPENDENCY_IDENTITY_CONFLICT".equals(code)) return null;
        var e = read(expected);
        var a = read(actual);
        // 新问题保存明确依赖类型；旧问题仅在原始发货中的客户编号能够印证时复核。
        var types = e.get("objectTypes") instanceof List<?> values ? values : List.of();
        List<String> ids =
                a.get("sourceIds") instanceof List<?> values
                        ? values.stream()
                                .filter(Objects::nonNull)
                                .map(Object::toString)
                                .filter(DhbShipmentRecoveryReview::meaningful)
                                .distinct()
                                .toList()
                        : List.of();
        if (ids.isEmpty()) return null;
        if (types.size() == 1
                && Set.of("CUSTOMER", "WAREHOUSE", "PRODUCT_SPU", "PRODUCT_SKU", "SALES_ORDER")
                        .contains(types.getFirst().toString()))
            return new Requirement(types.getFirst().toString(), ids);
        try {
            var shipment = JSON.readValue(payload, Shipment.class);
            var customerIds =
                    new HashSet<>(
                            Arrays.asList(shipment.customerGuid(), shipment.customerNumber()));
            if (ids.stream().allMatch(customerIds::contains))
                return new Requirement("CUSTOMER", ids);
        } catch (RuntimeException ignored) {
            // 缺乏可核对的旧证据不能自动恢复，仍保留原问题与人工处理入口。
        }
        return null;
    }

    private static boolean meaningful(String id) {
        return id != null && !id.isBlank() && !"0".equals(id);
    }

    private static Map<?, ?> read(String body) {
        try {
            return body == null ? Map.of() : JSON.readValue(body, Map.class);
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    record Requirement(String type, List<String> ids) {}

    record Key(String type, String id) {}
}
