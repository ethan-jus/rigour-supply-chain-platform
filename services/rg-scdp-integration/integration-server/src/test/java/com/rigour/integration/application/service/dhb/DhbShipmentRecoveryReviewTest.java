package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.integration.api.v1.model.*;
import com.rigour.integration.application.port.out.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;

class DhbShipmentRecoveryReviewTest {
    final UUID tenant = UUID.randomUUID(), connector = UUID.randomUUID(), run = UUID.randomUUID();
    final DhbIncrementalWorkStore work = mock(DhbIncrementalWorkStore.class);
    final DhbIntegrationStore issues = mock(DhbIntegrationStore.class);
    final DhbSyncStore mappings = mock(DhbSyncStore.class);
    final DhbShipmentRecoveryReview service = new DhbShipmentRecoveryReview(work, issues, mappings);
    final Instant now = Instant.parse("2026-11-17T06:00:00Z");
    final JsonMapper json =
            JsonMapper.builder()
                    .disable(
                            tools.jackson.databind.DeserializationFeature
                                    .FAIL_ON_NULL_FOR_PRIMITIVES)
                    .build();
    final DhbIncrementalWorkStore.Item item =
            new DhbIncrementalWorkStore.Item("FH1", "HASH", "{\"customerNumber\":\"C1\"}", now);

    void evidence(String code, String expected, String actual) {
        when(work.manualShipments(tenant, connector, 1000, now)).thenReturn(List.of(item));
        when(issues.openException(tenant, connector, "SALES_SHIPMENT", "FH1"))
                .thenReturn(
                        json.readValue(
                                json.writeValueAsString(
                                        Map.of("runId", run, "lastErrorCode", code)),
                                DhbSyncExceptionView.class));
        when(issues.openReconciliation(tenant, run, "SALES_SHIPMENT", "FH1"))
                .thenReturn(
                        json.readValue(
                                json.writeValueAsString(
                                        Map.of(
                                                "expectedValueJson",
                                                expected,
                                                "actualValueJson",
                                                actual)),
                                DhbSyncReconciliationCaseView.class));
    }

    DhbSyncStore.ExternalObjectMapping mapping(String source, long target) {
        return new DhbSyncStore.ExternalObjectMapping(
                UUID.randomUUID(),
                "CUSTOMER",
                source,
                source,
                "CRM",
                "CUSTOMER",
                target,
                "LOCAL" + target,
                "ACTIVE",
                null);
    }

    @Test
    void unitMaintenanceReturnsToOriginalValidationWithoutInventingUnitOrMapping() {
        evidence("DHB_SHIPMENT_LINE_UNIT_MISSING", "{}", "{}");
        when(work.resumeShipment(tenant, connector, item, now)).thenReturn(true);
        assertThat(service.reconsider(tenant, connector, now)).isOne();
        verifyNoInteractions(mappings);
        verify(work).resumeShipment(tenant, connector, item, now);
    }

    @Test
    void restoredOriginalSaleResumesSameWorkAndOnlyOnce() {
        evidence("DHB_SHIPMENT_ORDER_MAPPING_MISSING", "{}", "{\"sourceOrderNo\":\"DH1\"}");
        when(mappings.findActiveMapping(tenant, connector, "SALES_ORDER", "DH1"))
                .thenReturn(
                        new DhbSyncStore.ExternalObjectMapping(
                                UUID.randomUUID(),
                                "SALES_ORDER",
                                "DH1",
                                "DH1",
                                "ORDER",
                                "SALES_ORDER",
                                7L,
                                "SO1",
                                "ACTIVE",
                                null));
        when(work.resumeShipment(tenant, connector, item, now)).thenReturn(true, false);
        assertThat(service.reconsider(tenant, connector, now)).isOne();
        assertThat(service.reconsider(tenant, connector, now)).isZero();
        verify(issues, times(2)).openException(tenant, connector, "SALES_SHIPMENT", "FH1");
    }

    @Test
    void missingOrStillBlockedCustomerDoesNotTriggerRemoteOrBusinessWrites() {
        evidence("DHB_DEPENDENCY_IDENTITY_CONFLICT", "{}", "{\"sourceIds\":[\"C1\"]}");
        when(mappings.dependencyAutoSyncBlocked(
                        tenant, connector, List.of("CUSTOMER"), List.of("C1")))
                .thenReturn(true);
        assertThat(service.reconsider(tenant, connector, now)).isZero();
        verify(work, never()).resumeShipment(any(), any(), any(), any());
        verify(mappings, never()).upsertExternalObjectMapping(any(), any(), any());
        verify(work).deferShipmentReview(tenant, connector, item, now.plusSeconds(300));
    }

    @Test
    void legacyCustomerEvidenceMustAgreeWithTheOriginalShipment() {
        evidence("DHB_DEPENDENCY_IDENTITY_CONFLICT", "{}", "{\"sourceIds\":[\"C1\"]}");
        when(mappings.findActiveMapping(tenant, connector, "CUSTOMER", "C1"))
                .thenReturn(mapping("C1", 3));
        when(work.resumeShipment(tenant, connector, item, now)).thenReturn(true);
        assertThat(service.reconsider(tenant, connector, now)).isOne();
        assertThat(
                        DhbShipmentRecoveryReview.requirement(
                                "DHB_DEPENDENCY_IDENTITY_CONFLICT",
                                "{}",
                                "{\"sourceIds\":[\"OTHER\"]}",
                                item.payload()))
                .isNull();
    }

    @Test
    void conflictingAliasesNeverAutomaticallyChooseOneTarget() {
        evidence(
                "DHB_DEPENDENCY_IDENTITY_CONFLICT",
                "{\"objectTypes\":[\"CUSTOMER\"]}",
                "{\"sourceIds\":[\"C1\",\"GUID1\"]}");
        when(mappings.findActiveMapping(tenant, connector, "CUSTOMER", "C1"))
                .thenReturn(mapping("C1", 3));
        when(mappings.findActiveMapping(tenant, connector, "CUSTOMER", "GUID1"))
                .thenReturn(mapping("GUID1", 4));
        assertThat(service.reconsider(tenant, connector, now)).isZero();
        verify(work, never()).resumeShipment(any(), any(), any(), any());
    }

    @Test
    void missingOriginalTimeOrUncertainTransferCannotBeInvented() {
        assertThat(
                        DhbShipmentRecoveryReview.requirement(
                                "DHB_ORDER_BUSINESS_TIME_MISSING", "{}", "{}", item.payload()))
                .isNull();
        assertThat(
                        DhbShipmentRecoveryReview.requirement(
                                "DHB_TRANSFER_TARGET_WAREHOUSE_MAPPING_MISSING",
                                "{}",
                                "{}",
                                item.payload()))
                .isNull();
        assertThat(
                        DhbDependencyRequirement.from(
                                        "DHB_ORDER_MAPPING_MISSING",
                                        "{\"required\":\"客户 mapping\"}",
                                        "{\"sourceIds\":[\"0\",\"C1\"]}")
                                .sourceId())
                .isEqualTo("C1");
    }
}
