package com.rigour.integration.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.rigour.integration.api.v1.model.DhbApiModels.ExternalObjectMappingCommand;
import com.rigour.integration.infrastructure.persistence.entity.ExternalObjectMappingEntity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

class DhbAutomaticMappingProtectionTest {
    ExternalObjectMappingCommand command(Long id) {
        return new ExternalObjectMappingCommand(
                UUID.randomUUID(),
                "DINGHUOBAO",
                "WAREHOUSE",
                "W1",
                "01",
                "ERP",
                "WAREHOUSE",
                id,
                "W001",
                "ACTIVE",
                null,
                null,
                null,
                null,
                null,
                null);
    }

    ExternalObjectMappingEntity row(String status) {
        var row = new ExternalObjectMappingEntity();
        row.mappingStatus = status;
        row.internalDomain = "ERP";
        row.internalObjectType = "WAREHOUSE";
        row.internalObjectId = 3L;
        return row;
    }

    @ParameterizedTest
    @ValueSource(strings = {"REMOVED", "CONFLICT", "IGNORED"})
    void automatedCatalogueRefreshKeepsHumanDecision(String status) {
        assertThat(MybatisPlusDhbIntegrationStore.preserveExistingMapping(row(status), command(3L)))
                .isTrue();
    }

    @Test
    void activeIdentityCanRefreshButCannotBeReboundToDifferentTarget() {
        assertThat(
                        MybatisPlusDhbIntegrationStore.preserveExistingMapping(
                                row("ACTIVE"), command(3L)))
                .isFalse();
        assertThat(
                        MybatisPlusDhbIntegrationStore.preserveExistingMapping(
                                row("ACTIVE"), command(4L)))
                .isTrue();
        assertThat(MybatisPlusDhbIntegrationStore.preserveExistingMapping(null, command(3L)))
                .isFalse();
    }
}
