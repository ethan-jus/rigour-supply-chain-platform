package com.rigour.tenant.iam.infrastructure.persistence.settings;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.rigour.tenant.iam.application.port.out.AppEmployeeClient;

class AuthorizationReadScopeTest {
    @Test void nestedReadsReuseOnlySuccessfulResultsAndAlwaysClearAfterFailure() {
        var jdbc = mock(JdbcTemplate.class);
        var settings = new JdbcAppSettingsStore(jdbc, mock(PlatformTransactionManager.class), mock(AppEmployeeClient.class));
        UUID first = UUID.randomUUID(), next = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(byte[].class)))
                .thenReturn(JdbcAppSettingsStore.bin(first), JdbcAppSettingsStore.bin(next));
        assertThatThrownBy(() -> settings.authorizationRead(() -> {
            assertThat(settings.applicationId()).isEqualTo(first);
            assertThat(settings.authorizationRead(settings::applicationId)).isEqualTo(first);
            throw new IllegalStateException("failed authorization");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(settings.authorizationRead(settings::applicationId)).isEqualTo(next);
        verify(jdbc, times(2)).queryForObject(anyString(), eq(byte[].class));
    }
}
