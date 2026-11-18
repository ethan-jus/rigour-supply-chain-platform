package com.rigour.integration.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.rigour.integration.api.v1.model.DhbApiModels.ExternalObjectMappingCommand;
import com.rigour.integration.infrastructure.persistence.entity.ExternalObjectMappingEntity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

class DhbAutomaticMappingProtectionTest {
    private final UUID serviceActor = UUID.randomUUID();

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
        assertThat(MybatisPlusDhbIntegrationStore.preserveExistingMapping(row(status), command(3L), serviceActor))
                .isTrue();
    }

    @Test
    void activeIdentityCanRefreshButCannotBeReboundToDifferentTarget() {
        assertThat(
                        MybatisPlusDhbIntegrationStore.preserveExistingMapping(
                                row("ACTIVE"), command(3L), serviceActor))
                .isFalse();
        assertThat(
                        MybatisPlusDhbIntegrationStore.preserveExistingMapping(
                                row("ACTIVE"), command(4L), serviceActor))
                .isTrue();
        assertThat(MybatisPlusDhbIntegrationStore.preserveExistingMapping(null, command(3L), serviceActor))
                .isFalse();
    }

    @Test
    void owningDomainCanRecoverItsUnboundConflictAfterReview() {
        var row = ownedConflict();
        row.internalObjectId = null;
        assertThat(preserve(row, reviewed(3L))).isFalse();
        assertThat(preserve(ownedConflict(), reviewed(3L))).isFalse();
        assertThat(preserve(ownedConflict(), reviewed(4L))).isTrue();
    }

    @Test
    void manualDecisionAndDifferentDomainCannotBeOverwrittenByRecovery() {
        var row = ownedConflict();
        row.updatedBy = binary(UUID.randomUUID());
        assertThat(preserve(row, reviewed(3L))).isTrue();
        row = ownedConflict();
        row.createdBy = binary(UUID.randomUUID());
        assertThat(preserve(row, reviewed(3L))).isTrue();
        row = ownedConflict();
        row.internalDomain = "CRM";
        assertThat(preserve(row, reviewed(3L))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REMOVED", "IGNORED"})
    void automaticRecoveryNeverRestoresDisabledMappings(String status) {
        var row = ownedConflict();
        row.mappingStatus = status;
        assertThat(preserve(row, reviewed(3L))).isTrue();
    }

    @Test
    void deletedSourceOrStaleReviewCannotRestoreConflict() {
        var row = ownedConflict();
        row.deletedAt = LocalDateTime.of(2026, 11, 17, 0, 0);
        assertThat(preserve(row, reviewed(3L))).isTrue();
        row = ownedConflict();
        row.sourceDeletedAt = LocalDateTime.of(2026, 11, 17, 0, 0);
        assertThat(preserve(row, reviewed(3L))).isTrue();
        row = ownedConflict();
        row.lastSeenAt = LocalDateTime.of(2026, 11, 18, 0, 0);
        assertThat(preserve(row, reviewed(3L))).isTrue();
        row = ownedConflict();
        row.lastSeenRunId = null;
        assertThat(preserve(row, reviewed(3L))).isTrue();
        assertThat(preserve(ownedConflict(), command(3L))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER_CASUAL_EXCLUDED", "CUSTOMER_MANUALLY_EXCLUDED"})
    void crmCanRetireOnlyItsOwnCustomerConflict(String reason) {
        var row = ownedConflict();
        row.internalDomain = "CRM";
        row.internalObjectType = "CUSTOMER";
        assertThat(preserve(row, customerPolicyCommand("IGNORED", reason))).isFalse();
        row.updatedBy = binary(UUID.randomUUID());
        assertThat(preserve(row, customerPolicyCommand("IGNORED", reason))).isTrue();
    }

    @Test
    void crmCanResumeItsCasualPolicyButCannotRestoreUserDeletedCustomer() {
        var row = ownedConflict();
        row.internalDomain = "CRM";
        row.internalObjectType = "CUSTOMER";
        row.mappingStatus = "IGNORED";
        row.conflictReason = "CUSTOMER_CASUAL_EXCLUDED";
        assertThat(preserve(row, customerPolicyCommand("ACTIVE", null))).isFalse();
        row.conflictReason = "CUSTOMER_MANUALLY_EXCLUDED";
        assertThat(preserve(row, customerPolicyCommand("ACTIVE", null))).isTrue();
    }

    private ExternalObjectMappingCommand customerPolicyCommand(String status, String reason) {
        var c = reviewed(3L);
        return new ExternalObjectMappingCommand(c.connectorId(), c.sourceSystem(), "CUSTOMER", "C1", "C1",
                "CRM", "CUSTOMER", c.internalObjectId(), c.internalObjectNo(), status,
                c.lastSeenRunId(), c.lastSeenAt(), null, null, reason, "客户同步规则排除");
    }

    private boolean preserve(ExternalObjectMappingEntity row, ExternalObjectMappingCommand command) {
        return MybatisPlusDhbIntegrationStore.preserveExistingMapping(row, command, serviceActor);
    }

    private ExternalObjectMappingEntity ownedConflict() {
        var row = row("CONFLICT");
        row.createdBy = binary(serviceActor);
        row.updatedBy = binary(serviceActor);
        row.lastSeenRunId = binary(UUID.randomUUID());
        row.lastSeenAt = LocalDateTime.of(2026, 11, 16, 0, 0);
        return row;
    }

    private ExternalObjectMappingCommand reviewed(Long target) {
        var original = command(target);
        return new ExternalObjectMappingCommand(original.connectorId(), original.sourceSystem(),
                original.sourceObjectType(), original.sourceObjectId(), original.sourceObjectNo(),
                original.internalDomain(), original.internalObjectType(), target, original.internalObjectNo(),
                "ACTIVE", UUID.randomUUID(), Instant.parse("2026-11-17T00:00:00Z"), null, null, null, null);
    }

    private static byte[] binary(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
