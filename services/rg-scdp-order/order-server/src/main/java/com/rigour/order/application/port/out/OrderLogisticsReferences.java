package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.LogisticsCostSourceView;

import java.util.List;
import java.util.Set;

public interface OrderLogisticsReferences {
    Set<String> validCodes(String tenant, String code);

    List<LogisticsCostSourceView> sources(
            String leg, String keyword, List<Long> ids, String action);
}
