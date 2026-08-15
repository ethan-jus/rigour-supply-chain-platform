package com.rigour.integration.infrastructure.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

class DhbConnectorLeaseManagerTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb900-0000-7000-8000-000000000001");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb900-0000-7000-8000-000000000002");

    @Test
    void acquiresRenewsAndReleasesOnlyByExactToken() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0, 1, 1, 1);
        DhbConnectorLeaseManager manager = new DhbConnectorLeaseManager(
                jdbc, new DhbConnectorLeaseProperties());

        DhbConnectorLeaseManager.Lease lease = manager.acquire(TENANT_ID, CONNECTOR_ID, "erp-service");
        manager.renew(TENANT_ID, CONNECTOR_ID, lease.token());
        manager.release(TENANT_ID, CONNECTOR_ID, lease.token());

        assertThat(lease.token()).isNotBlank();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(4)).update(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues().get(1)).contains("TIMESTAMPADD");
        assertThat(sql.getAllValues().get(2)).contains("lease_token=?", "expires_at>UTC_TIMESTAMP");
        assertThat(sql.getAllValues().get(3)).contains("lease_token=?");
    }

    @Test
    void duplicateConnectorLeaseReturnsStableConflictCode() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenReturn(0).thenThrow(new DuplicateKeyException("duplicate"));
        DhbConnectorLeaseManager manager = new DhbConnectorLeaseManager(
                jdbc, new DhbConnectorLeaseProperties());

        assertThatThrownBy(() -> manager.acquire(TENANT_ID, CONNECTOR_ID, "crm-service"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SYNC_ALREADY_RUNNING));
    }
}
