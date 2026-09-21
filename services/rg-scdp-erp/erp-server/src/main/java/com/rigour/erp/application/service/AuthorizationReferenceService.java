package com.rigour.erp.application.service;

import com.rigour.erp.api.v1.model.AuthorizationReferenceView;
import com.rigour.erp.application.port.out.ErpInventoryWarehouseStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public final class AuthorizationReferenceService {
    private final ErpInventoryWarehouseStore store;

    public AuthorizationReferenceService(ErpInventoryWarehouseStore store) {
        this.store = store;
    }

    public List<AuthorizationReferenceView> references() {
        var actor = AuthorizationContext.requireCurrent();
        if (!"SERVICE".equals(actor.principalScope()) || actor.tenantId() == null)
            throw new AuthorizationDeniedException("service-scope-reference");
        AuthorizationContext.requirePermission("supply:scope:reference-read");
        List<AuthorizationReferenceView> result = new ArrayList<>();
        int begin = 0;
        while (true) {
            var page =
                    store.warehouses(
                            actor.tenantId().toString(),
                            begin,
                            200,
                            new ErpInventoryWarehouseStore.WarehouseSearchCriteria(
                                    null, null, null, null, null));
            for (var d : page.items())
                result.add(
                        new AuthorizationReferenceView(
                                d.id().toString(),
                                d.warehouseName(),
                                null,
                                d.statusCode(),
                                d.revision() == null ? 0 : d.revision(),
                                d.regionCode()));
            begin += page.items().size();
            if (begin >= page.total() || page.items().isEmpty()) break;
            if (begin >= 20000) throw new IllegalStateException("仓库数量超过单次授权目录上限");
        }
        return List.copyOf(result);
    }
}
