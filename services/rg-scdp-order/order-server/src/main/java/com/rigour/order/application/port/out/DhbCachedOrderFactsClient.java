package com.rigour.order.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DhbCachedOrderFactsClient {
    record Facts(String orderNo, String customerName, String employeeCode, String employeeName,
                 LocalDate documentDate, BigDecimal orderAmount) {}

    List<Facts> read(String tenant, UUID connector, List<String> orderNos);
}
