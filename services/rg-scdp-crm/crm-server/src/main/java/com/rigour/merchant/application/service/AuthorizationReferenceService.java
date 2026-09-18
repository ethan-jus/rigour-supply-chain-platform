package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.model.AuthorizationReferenceView;
import com.rigour.merchant.application.port.out.CrmCustomerQueryStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public final class AuthorizationReferenceService {
    private final CrmCustomerQueryStore store;

    public AuthorizationReferenceService(CrmCustomerQueryStore store) {
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
            var page = store.customerAreas(actor.tenantId(), begin, 200, null);
            for (var d : page.items())
                result.add(
                        new AuthorizationReferenceView(
                                d.code(),
                                d.name(),
                                d.parentCode(),
                                Set.of("ABSENT", "DELETED")
                                                .contains(
                                                        d.sourcePresence() == null
                                                                ? ""
                                                                : d.sourcePresence())
                                        ? "INACTIVE"
                                        : d.status(),
                                d.revision() == null ? 0 : d.revision()));
            begin += page.items().size();
            if (begin >= page.total() || page.items().isEmpty()) break;
            if (begin >= 20000) throw new IllegalStateException("客户地区数量超过单次授权目录上限");
        }
        return List.copyOf(result);
    }
}
