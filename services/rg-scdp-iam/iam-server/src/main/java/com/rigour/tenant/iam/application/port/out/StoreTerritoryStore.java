package com.rigour.tenant.iam.application.port.out;

import com.rigour.tenant.iam.application.model.settings.StoreAccessSnapshot.Area;
import com.rigour.tenant.iam.application.model.settings.StoreTerritoryModels;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import java.util.List;

public interface StoreTerritoryStore {
    List<Area> departments(String tenant, List<String> departments);
    record Ceiling(boolean enabled, List<Area> areas) {}
    Ceiling personal(String tenant, String user);
    StoreTerritoryModels.Context management(Actor actor);
    void saveTerritory(Actor actor, String department, StoreTerritoryModels.Update command);
}
