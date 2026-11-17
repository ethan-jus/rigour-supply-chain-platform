package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.SalesShipmentSnapshotCommand;

public interface SalesShipmentSnapshotStore {
    Long save(String tenant, SalesShipmentSnapshotCommand command);
}
