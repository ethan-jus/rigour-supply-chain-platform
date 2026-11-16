package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.*;

import com.rigour.order.api.v1.model.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;

class DhbShipmentTransportMapperTest {
    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void originalIdentityMultiplePackagesAddressAndGoodsAreRetainedWithoutInventingFreight() {
        var shipment =
                new DhbShipmentTransportMapper.ShippingContext(
                        11L,
                        3L,
                        "CUS1",
                        "WH",
                        java.time.Instant.parse("2026-11-13T01:00:00Z"),
                        null);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ships_id", "D1");
        detail.put("status", "receivedin");
        detail.put("logistics_name", "顺丰");
        detail.put(
                "express_num", List.of(Map.of("express_num", "SF001"), Map.of("number", "SF002")));
        detail.put(
                "address",
                Map.of(
                        "company",
                        "星河门店",
                        "contact",
                        "王先生",
                        "mobile",
                        "13900000000",
                        "address",
                        "武汉市收货路1号"));
        detail.put("list", List.of(Map.of("goods_num", "G1", "ships_number", 3)));
        detail.put("unknown_future_field", Map.of("foo", "bar"));
        var payload = Map.<String, Object>of("detail", detail, "list", Map.of());
        var result =
                DhbShipmentTransportMapper.command(
                        UUID.randomUUID(), "FH001", null, shipment, payload, "SF", "THIRD_PARTY");
        assertThat(result.trackingNumbers()).containsExactly("SF001", "SF002");
        assertThat(result.shipmentId()).isEqualTo("D1");
        assertThat(result.salesOrderId()).isEqualTo(11L);
        assertThat(result.receiver().contactName()).isEqualTo("王先生");
        assertThat(result.goods()).hasSize(1);
        assertThat(result.sourceFields().get("detail")).isEqualTo(detail);
        assertThat(json.writeValueAsString(result)).doesNotContain("billAmount", "freightAmount");
    }

    @Test
    void unrecognizedTrackingStructureIsNotFlattenedIntoAFakeNumber() {
        var shipment =
                new DhbShipmentTransportMapper.ShippingContext(11L, null, null, null, null, null);
        var result =
                DhbShipmentTransportMapper.command(
                        UUID.randomUUID(),
                        "FH001",
                        null,
                        shipment,
                        Map.of(
                                "detail",
                                Map.of("express_num", Map.of("unknown_key", List.of("SF001"))),
                                "list",
                                Map.of()),
                        null,
                        null);
        assertThat(result.trackingNumbers()).isEmpty();
        assertThat(result.sourceFields()).containsKey("detail");
        assertThat(result.sourceStatus()).isEqualTo("unknown");
    }
}
