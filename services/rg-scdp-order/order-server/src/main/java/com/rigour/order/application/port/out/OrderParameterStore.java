package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.OrderParameterApi.*;

public interface OrderParameterStore {
    Parameter maximumManualLines(String tenant);

    Parameter saveMaximumManualLines(String tenant, String actor, Change command);
}
