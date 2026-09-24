package com.rigour.tenant.iam.application.port.out;

import java.util.UUID;

public interface SelfPasswordStore {
    void change(UUID tenantId, UUID userId, String currentPassword, String newPassword);
}
