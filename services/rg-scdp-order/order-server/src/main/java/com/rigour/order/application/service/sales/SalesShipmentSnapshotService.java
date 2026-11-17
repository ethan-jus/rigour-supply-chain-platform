package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.model.SalesShipmentSnapshotCommand;
import com.rigour.order.application.port.out.SalesShipmentSnapshotStore;
import com.rigour.shared.context.*;

import org.springframework.stereotype.Service;

@Service
public class SalesShipmentSnapshotService {
    private final SalesShipmentSnapshotStore store;

    public SalesShipmentSnapshotService(SalesShipmentSnapshotStore store) {
        this.store = store;
    }

    public Long save(SalesShipmentSnapshotCommand c) {
        var caller = AuthorizationContext.requireCurrent();
        if (!"SERVICE".equals(caller.principalScope())
                || caller.tenantId() == null
                || !caller.roles().contains("DHB_ORDER_SYNC_SERVICE"))
            throw new AuthorizationDeniedException("DHB shipment sync only");
        if (c == null
                || c.connectorId() == null
                || c.shipmentNo() == null
                || c.shipmentNo().isBlank()
                || c.shipmentNo().length() > 50
                || c.payload().isEmpty()) throw new IllegalArgumentException("来源发货单标识和资料不能为空");
        if (!java.util.Set.of("CREATED", "SHIPPED", "SIGNED", "CANCELLED").contains(c.statusCode()))
            throw new IllegalArgumentException("来源发货状态无效");
        return store.save(caller.tenantId().toString(), c);
    }
}
