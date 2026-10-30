package com.rigour.tenant.iam.application.service.settings;

import com.rigour.tenant.iam.application.model.settings.StoreTerritoryModels;
import com.rigour.tenant.iam.application.port.out.StoreTerritoryStore;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import org.springframework.stereotype.Service;

@Service
public final class StoreTerritoryService {
    private final StoreTerritoryStore store;
    public StoreTerritoryService(StoreTerritoryStore store) { this.store = store; }
    public com.rigour.settings.api.v1.model.AdminDivisionCatalog catalog(Actor actor) { return store.catalog(actor); }
    public StoreTerritoryModels.Context context(Actor actor) { return store.management(actor); }
    public void save(Actor actor, String department, StoreTerritoryModels.Update command) {
        store.saveTerritory(actor, department, command);
    }
}
