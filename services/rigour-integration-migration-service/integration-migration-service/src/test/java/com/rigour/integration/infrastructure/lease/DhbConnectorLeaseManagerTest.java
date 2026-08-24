package com.rigour.integration.infrastructure.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.integration.infrastructure.persistence.entity.IntegrationConnectorSyncLeaseEntity;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationConnectorSyncLeaseMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class DhbConnectorLeaseManagerTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb900-0000-7000-8000-000000000001");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb900-0000-7000-8000-000000000002");

    @Test
    void acquiresRenewsAndReleasesOnlyByExactToken() {
        IntegrationConnectorSyncLeaseMapper mapper = mock(IntegrationConnectorSyncLeaseMapper.class);
        when(mapper.insert(any(IntegrationConnectorSyncLeaseEntity.class))).thenReturn(1);
        when(mapper.update(any(), any())).thenReturn(1);
        when(mapper.delete(any())).thenReturn(0, 1);
        DhbConnectorLeaseManager manager = new DhbConnectorLeaseManager(
                mapper, new DhbConnectorLeaseProperties());

        DhbConnectorLeaseManager.Lease lease = manager.acquire(TENANT_ID, CONNECTOR_ID, "erp-service");
        manager.renew(TENANT_ID, CONNECTOR_ID, lease.token());
        manager.release(TENANT_ID, CONNECTOR_ID, lease.token());

        assertThat(lease.token()).isNotBlank();
        ArgumentCaptor<IntegrationConnectorSyncLeaseEntity> leaseRow =
                ArgumentCaptor.forClass(IntegrationConnectorSyncLeaseEntity.class);
        verify(mapper).insert(leaseRow.capture());
        assertThat(leaseRow.getValue().leaseToken).isEqualTo(lease.token());
        assertThat(leaseRow.getValue().ownerId).isEqualTo("erp-service");
        verify(mapper).update(any(), any());
        verify(mapper, org.mockito.Mockito.times(2)).delete(any());
    }

    @Test
    void duplicateConnectorLeaseReturnsStableConflictCode() {
        IntegrationConnectorSyncLeaseMapper mapper = mock(IntegrationConnectorSyncLeaseMapper.class);
        when(mapper.delete(any())).thenReturn(0);
        when(mapper.insert(any(IntegrationConnectorSyncLeaseEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        DhbConnectorLeaseManager manager = new DhbConnectorLeaseManager(
                mapper, new DhbConnectorLeaseProperties());

        assertThatThrownBy(() -> manager.acquire(TENANT_ID, CONNECTOR_ID, "crm-service"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SYNC_ALREADY_RUNNING));
    }
}
