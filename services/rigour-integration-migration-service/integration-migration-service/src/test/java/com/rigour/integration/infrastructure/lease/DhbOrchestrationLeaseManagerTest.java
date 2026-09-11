package com.rigour.integration.infrastructure.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.integration.infrastructure.persistence.entity.IntegrationDhbOrchestrationLeaseEntity;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationDhbOrchestrationLeaseMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class DhbOrchestrationLeaseManagerTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb900-0000-7000-8000-000000000101");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb900-0000-7000-8000-000000000102");

    @Test
    void executeAcquiresAndReleasesDistributedOrchestrationLease() {
        IntegrationDhbOrchestrationLeaseMapper mapper =
                mock(IntegrationDhbOrchestrationLeaseMapper.class);
        when(mapper.delete(any())).thenReturn(0, 1);
        when(mapper.insert(any(IntegrationDhbOrchestrationLeaseEntity.class))).thenReturn(1);
        DhbOrchestrationLeaseManager manager = new DhbOrchestrationLeaseManager(
                mapper, new DhbConnectorLeaseProperties());
        try {
            String result = manager.execute(TENANT_ID, CONNECTOR_ID,
                    "rigour-integration-migration-service:DHB_ORCHESTRATION", () -> "ok");

            assertThat(result).isEqualTo("ok");
            ArgumentCaptor<IntegrationDhbOrchestrationLeaseEntity> leaseRow =
                    ArgumentCaptor.forClass(IntegrationDhbOrchestrationLeaseEntity.class);
            verify(mapper).insert(leaseRow.capture());
            assertThat(leaseRow.getValue().leaseToken).isNotBlank();
            assertThat(leaseRow.getValue().ownerId)
                    .isEqualTo("rigour-integration-migration-service:DHB_ORCHESTRATION");
            verify(mapper, org.mockito.Mockito.times(2)).delete(any());
        } finally {
            manager.close();
        }
    }

    @Test
    void duplicateOrchestrationLeaseReturnsStableConflictCode() {
        IntegrationDhbOrchestrationLeaseMapper mapper =
                mock(IntegrationDhbOrchestrationLeaseMapper.class);
        when(mapper.delete(any())).thenReturn(0);
        when(mapper.insert(any(IntegrationDhbOrchestrationLeaseEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        DhbOrchestrationLeaseManager manager = new DhbOrchestrationLeaseManager(
                mapper, new DhbConnectorLeaseProperties());
        try {
            assertThatThrownBy(() -> manager.execute(TENANT_ID, CONNECTOR_ID,
                    "rigour-integration-migration-service:DHB_ORCHESTRATION", () -> "ignored"))
                    .isInstanceOfSatisfying(BusinessException.class, error ->
                            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SYNC_ALREADY_RUNNING));
        } finally {
            manager.close();
        }
    }
}
