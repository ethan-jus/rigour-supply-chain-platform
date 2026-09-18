package com.rigour.tenant.iam.application.service.settings;

import com.rigour.tenant.iam.application.port.out.*;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public final class AppReferenceService {
    private final AppSettingsStore settings;
    private final AppReferenceClient references;

    public AppReferenceService(AppSettingsStore settings, AppReferenceClient references) {
        this.settings = settings;
        this.references = references;
    }

    public List<AppReferenceClient.Reference> references(Actor actor, String dimension) {
        var p = settings.context(actor).permissions();
        if (!p.contains("supply:role:grant") && !p.contains("supply:user:assign-role")
                && !("DEPARTMENT".equals(dimension) && p.contains("supply:user:read")))
            throw new org.springframework.security.access.AccessDeniedException("无权配置授权范围");
        return references.references(actor.tenantId(), dimension);
    }
}
