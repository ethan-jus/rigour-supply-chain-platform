package com.rigour.merchant.application.port.out;

import com.rigour.merchant.api.v1.CustomerResponsibilityApi.*;

public interface CustomerResponsibilityStore {
    Overview overview(String tenant, long customer);

    Overview transfer(String tenant, long customer, Change c, String actor);

    Overview resolve(String tenant, long customer, long conflict, Resolution c, String actor);
}
