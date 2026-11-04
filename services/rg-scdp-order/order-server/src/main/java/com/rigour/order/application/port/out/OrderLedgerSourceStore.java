package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.*;
import java.util.List;

public interface OrderLedgerSourceStore {
    List<OrderDeliveryCostSourceView> sales(String tenant, LogisticsLedgerSourceQuery query, String action, Long warehouseId);
}
