package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.ShipmentTransportModels.*;

import java.util.*;

public interface ShipmentTransportStore {
    Source sync(
            String tenant,
            String actor,
            Command command,
            LogisticsCostCommand transport,
            LogisticsCostSourceView order,
            Long warehouseId);

    int backfillCarrier(String tenant, String actor, CarrierBackfill command);

    Map<String, List<Source>> byCosts(String tenant, List<String> costIds);
}
