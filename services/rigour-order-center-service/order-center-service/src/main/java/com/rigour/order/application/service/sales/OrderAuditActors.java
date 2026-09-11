package com.rigour.order.application.service.sales;

import com.rigour.shared.context.CallerIdentity;

final class OrderAuditActors {
    private static final String SYSTEM_ACTOR = "SYSTEM";

    private OrderAuditActors() {
    }

    static String writeActor(CallerIdentity actor) {
        if (actor == null || actor.principalId() == null) {
            return SYSTEM_ACTOR;
        }
        return "SERVICE".equals(actor.principalScope()) ? SYSTEM_ACTOR : actor.principalId().toString();
    }
}
