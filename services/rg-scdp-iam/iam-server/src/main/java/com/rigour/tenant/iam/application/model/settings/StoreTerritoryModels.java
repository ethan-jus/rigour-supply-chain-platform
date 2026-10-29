package com.rigour.tenant.iam.application.model.settings;

import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.tenant.iam.application.model.settings.StoreAccessSnapshot.Area;
import java.util.List;

public final class StoreTerritoryModels {
    private StoreTerritoryModels() {}

    public record Department(String id, String name, String parentId, String status, List<Area> areas) {}
    public record Context(long applicationVersion, boolean canWrite, AdminDivisionCatalog catalog,
                          List<Department> departments) {}
    public record Update(long applicationVersion, String catalogVersion, List<String> areaCodes, String reason) {}
}
