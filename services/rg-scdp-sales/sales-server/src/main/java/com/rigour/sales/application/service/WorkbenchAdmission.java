package com.rigour.sales.application.service;

import com.rigour.sales.application.port.out.SalesVoiceProfileRepository;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.util.UUID;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class WorkbenchAdmission {
    private final Environment environment;
    private final SalesVoiceProfileRepository voices;

    public WorkbenchAdmission(Environment environment, SalesVoiceProfileRepository voices) {
        this.environment = environment;
        this.voices = voices;
    }

    public boolean needsVoice(UUID tenant, UUID profile, String employeeCode) {
        String prefix = "rigour.workbench.voice." + tenant;
        if (!environment.getProperty(prefix + ".required", Boolean.class, environment.getProperty("rigour.workbench.voice-default-required", Boolean.class, false))) return false;
        var exempt = Binder.get(environment).bind(prefix + ".exempt-employees",
                org.springframework.boot.context.properties.bind.Bindable.listOf(String.class)).orElse(java.util.List.of());
        return !exempt.contains(employeeCode) && !voices.read(tenant, profile, "").registered();
    }

    public void checkVoice(UUID tenant, UUID profile, String employeeCode) {
        if (needsVoice(tenant, profile, employeeCode))
            throw new BusinessException(ErrorCode.SALES_VOICE_PROFILE_REQUIRED);
    }
}
