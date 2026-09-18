package com.rigour.settings.application.port.out;

import com.rigour.settings.api.v1.SettingsOperationAuditApi.Page;

public interface SettingsOperationAuditStore {
    Page audits(String tenant, String action, String keyword, int page, int pageSize);
}
