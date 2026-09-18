package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.OrderParameterApi.*;
import com.rigour.order.application.port.out.OrderParameterStore;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.tenant.iam.client.SupplyAuthorizationClient;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public final class OrderParameterService {
    private final OrderParameterStore store;
    private final SupplyAuthorizationClient permissions;

    public OrderParameterService(OrderParameterStore store, SupplyAuthorizationClient permissions) {
        this.store = store;
        this.permissions = permissions;
    }

    private CallerIdentity require(String action) {
        var actor = AuthorizationContext.requireCurrent();
        if (!"TENANT".equals(actor.principalScope())
                || !permissions.authorization(actor, action).functionAllowed())
            throw new AuthorizationDeniedException(action);
        return actor;
    }

    public List<Parameter> list() {
        var actor = require("supply:parameter:read");
        return List.of(store.maximumManualLines(actor.tenantId().toString()));
    }

    public Parameter save(String code, Change c) {
        var actor = require("supply:parameter:update");
        if (!"SALES_ORDER_MAX_LINES".equals(code) || c == null)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "未知业务参数", List.of());
        return store.saveMaximumManualLines(
                actor.tenantId().toString(), actor.userId().toString(), c);
    }
}
