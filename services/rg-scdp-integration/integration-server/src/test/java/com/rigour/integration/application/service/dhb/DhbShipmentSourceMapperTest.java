package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class DhbShipmentSourceMapperTest {
    @Test
    void officialStateTimeAndTrackingSemantics() {
        var list =
                Map.<String, Object>of(
                        "ships_date",
                        "2026-11-16 11:12:13",
                        "create_date",
                        "2026-11-15 08:09:10",
                        "status",
                        "receivedin",
                        "express_num",
                        "SF123",
                        "client_name",
                        "旧客户");
        var detail =
                Map.<String, Object>of(
                        "ships_date",
                        "2026-11-16 14:15:16",
                        "status",
                        "shipped",
                        "express_num",
                        "",
                        "client_name",
                        "实际客户",
                        "address",
                        Map.of("consignee_phone", "123"));
        var c =
                DhbShipmentSourceMapper.command(
                        UUID.randomUUID(), "FH1", null, Map.of("list", list, "detail", detail));
        assertThat(c.statusCode()).isEqualTo("CREATED");
        assertThat(c.shipTime().toString()).isEqualTo("2026-11-16T06:15:16Z");
        assertThat(c.stockOutTime().toString()).isEqualTo("2026-11-15T00:09:10Z");
        assertThat(c.customerName()).isEqualTo("实际客户");
        assertThat(c.trackingNo()).isEmpty();
        assertThat(DhbShipmentSourceMapper.status("receivedin")).isEqualTo("SHIPPED");
        assertThat(DhbShipmentSourceMapper.status("received")).isEqualTo("SIGNED");
        assertThat(DhbShipmentSourceMapper.status("cancelled")).isEqualTo("CANCELLED");
    }

    @Test
    void missingShipDateNeverUsesCreationAndUnknownStateDoesNotPretendShipped() {
        var c =
                DhbShipmentSourceMapper.command(
                        UUID.randomUUID(),
                        "FH1",
                        null,
                        Map.of("list", Map.of("create_date", "2026-11-16 12:00:00")));
        assertThat(c.shipTime()).isNull();
        assertThat(c.stockOutTime()).isNotNull();
        assertThatThrownBy(() -> DhbShipmentSourceMapper.status("not-documented"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
