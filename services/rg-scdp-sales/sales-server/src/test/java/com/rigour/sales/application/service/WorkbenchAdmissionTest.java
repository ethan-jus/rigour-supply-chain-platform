package com.rigour.sales.application.service;

import com.rigour.sales.api.v1.SalesVoiceProfileApi.Profile;
import com.rigour.sales.application.port.out.SalesVoiceProfileRepository;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.core.exception.StateConflictException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkbenchAdmissionTest {
    final MockEnvironment environment = new MockEnvironment();
    final SalesVoiceProfileRepository voices = mock(SalesVoiceProfileRepository.class);
    final WorkbenchAdmission admission = new WorkbenchAdmission(environment, voices);

    @Test void buildsHaveIndependentRolloutWindows() {
        var policy = new WorkbenchReleasePolicy(environment);
        for (String platform : new String[]{"android", "ios", "harmony"}) {
            String prefix = "rigour.workbench.releases." + platform;
            environment.setProperty(prefix + ".latest-build", "227");
            environment.setProperty(prefix + ".minimum-build", "225");
            environment.setProperty(prefix + ".install-url", "https://example.org/workbench");
            assertThat(policy.release(platform).latestBuild()).isEqualTo(227);
            assertThatThrownBy(() -> policy.checkBuild(platform, "224")).isInstanceOf(StateConflictException.class);
            policy.checkBuild(platform, "225");
            policy.checkBuild(platform, "228");
        }
        environment.setProperty("rigour.workbench.releases.ios.minimum-build", "224");
        policy.checkBuild("ios", "224");
        assertThatThrownBy(() -> policy.checkBuild("android", "224")).isInstanceOf(StateConflictException.class);
        policy.checkBuild(null, null);
        environment.setProperty("rigour.workbench.require-version-headers", "true");
        assertThatThrownBy(() -> policy.checkBuild(null, null)).isInstanceOf(StateConflictException.class);
    }

    @Test void enrollmentDeletionAndManualExceptionApplyToTheCurrentEmployeeAndTenant() {
        var tenant = UUID.randomUUID();
        var profile = UUID.randomUUID();
        String prefix = "rigour.workbench.voice." + tenant;
        environment.setProperty(prefix + ".required", "true");
        when(voices.read(tenant, profile, "")).thenReturn(new Profile(false, "", 0, null),
                new Profile(true, "", 1, null), new Profile(false, "", 2, null));
        assertThatThrownBy(() -> admission.checkVoice(tenant, profile, "E123")).isInstanceOf(BusinessException.class);
        admission.checkVoice(tenant, profile, "E123");
        assertThatThrownBy(() -> admission.checkVoice(tenant, profile, "E123")).isInstanceOf(BusinessException.class);
        environment.setProperty(prefix + ".exempt-employees", "E123,E124");
        admission.checkVoice(tenant, profile, "E123");
        admission.checkVoice(UUID.randomUUID(), profile, "E123");
        verify(voices, times(3)).read(tenant, profile, "");
    }
}
