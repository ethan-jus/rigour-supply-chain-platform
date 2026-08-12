package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DhbIntegrationServiceTest {

    @Test
    void qualifiesSkuSourceIdWithItsProductBecauseDhbOptionsIdIsNotGloballyUnique() {
        assertThat(DhbIntegrationService.normalizedSkuSourceId("P-1", "0"))
                .isEqualTo("P-1::0");
        assertThat(DhbIntegrationService.normalizedSkuSourceId("P-2", "0"))
                .isEqualTo("P-2::0");
    }

    @Test
    void rejectsMissingProductOrSkuSourceId() {
        assertThatThrownBy(() -> DhbIntegrationService.normalizedSkuSourceId("P-1", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("订货宝商品或SKU来源ID不能为空");
    }
}
