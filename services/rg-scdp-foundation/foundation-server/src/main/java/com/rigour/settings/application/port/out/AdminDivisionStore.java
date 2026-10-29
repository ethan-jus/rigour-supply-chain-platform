package com.rigour.settings.application.port.out;

import com.rigour.settings.api.v1.model.AdminDivisionCatalog;

public interface AdminDivisionStore {
    AdminDivisionCatalog published(String version);
}
