package com.rigour.merchant.application.port.out;

import com.rigour.merchant.api.v1.CustomerMemberResponsibilityApi.*;

import java.util.UUID;

public interface CustomerMemberResponsibilityStore {
    Page customers(
            String tenant,
            UUID userId,
            String mode,
            String keyword,
            String customerType,
            String regionCode,
            String status,
            int page,
            int size,
            String action);

    Preview preview(String tenant, UUID userId, PreviewCommand command, String actor);

    Applied apply(String tenant, UUID userId, ApplyCommand command, String actor);
}
