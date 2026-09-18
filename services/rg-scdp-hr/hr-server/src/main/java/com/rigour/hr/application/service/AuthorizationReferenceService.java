package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.AuthorizationReferenceView;
import com.rigour.hr.application.port.out.HrOrganizationStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public final class AuthorizationReferenceService {
    private final HrOrganizationStore store;

    public AuthorizationReferenceService(HrOrganizationStore store) {
        this.store = store;
    }

    public List<AuthorizationReferenceView> references() {
        var actor = AuthorizationContext.requireCurrent();
        if (!"SERVICE".equals(actor.principalScope()) || actor.tenantId() == null)
            throw new AuthorizationDeniedException("service-scope-reference");
        AuthorizationContext.requirePermission("supply:scope:reference-read");
        return store.departments(actor.tenantId().toString()).stream()
                .map(
                        d ->
                                new AuthorizationReferenceView(
                                        d.id().toString(),
                                        d.departmentName(),
                                        d.parentId() == null ? null : d.parentId().toString(),
                                        d.statusCode(),
                                        d.revision()))
                .toList();
    }
}
