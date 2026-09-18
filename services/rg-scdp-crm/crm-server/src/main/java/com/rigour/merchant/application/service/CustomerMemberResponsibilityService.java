package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.CustomerMemberResponsibilityApi.*;
import com.rigour.merchant.application.port.out.CustomerMemberResponsibilityStore;
import com.rigour.shared.context.*;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public final class CustomerMemberResponsibilityService {
    private final CustomerMemberResponsibilityStore store;

    public CustomerMemberResponsibilityService(CustomerMemberResponsibilityStore store) {
        this.store = store;
    }

    private CallerIdentity actor(boolean write) {
        var actor = AuthorizationContext.requireCurrent();
        if (!"TENANT".equals(actor.principalScope()))
            throw new AuthorizationDeniedException("supply:user:read");
        AuthorizationContext.requirePermission("supply:user:read");
        AuthorizationContext.requirePermission(write ? "crm:customer:assign-owner" : readAction());
        return actor;
    }

    private String readAction() {
        return AuthorizationContext.hasPermission("crm:customer:read")
                ? "crm:customer:read"
                : "crm:customer:assign-owner";
    }

    public Page customers(
            UUID userId,
            String mode,
            String keyword,
            String customerType,
            String regionCode,
            String status,
            int page,
            int size) {
        boolean candidates = "CANDIDATES".equals(mode);
        var actor = actor(candidates);
        return store.customers(
                actor.tenantId().toString(),
                userId,
                mode,
                keyword,
                customerType,
                regionCode,
                status,
                page,
                size,
                candidates ? "crm:customer:assign-owner" : readAction());
    }

    public Preview preview(UUID userId, PreviewCommand command) {
        var actor = actor(true);
        return store.preview(
                actor.tenantId().toString(), userId, command, actor.userId().toString());
    }

    public Applied apply(UUID userId, ApplyCommand command) {
        var actor = actor(true);
        return store.apply(actor.tenantId().toString(), userId, command, actor.userId().toString());
    }
}
