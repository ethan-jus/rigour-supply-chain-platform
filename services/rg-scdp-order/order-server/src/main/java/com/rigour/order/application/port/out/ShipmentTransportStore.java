package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.ShipmentTransportModels.*;

import java.util.*;

public interface ShipmentTransportStore {
    Command withShipmentLogisticsEdit(String tenant, Command source);

    Source sync(
            String tenant,
            String actor,
            Command command,
            LogisticsCostCommand transport,
            LogisticsCostSourceView order,
            Long warehouseId);

    void reconcileHuolala(String tenant, String actor, String orderNo, String costId);

    int reconcileShipmentOrders(String tenant, String actor, String costId);

    int backfillCarrier(String tenant, String actor, CarrierBackfill command);

    Map<String, List<Source>> byCosts(String tenant, List<String> costIds);
}
