package com.rigour.order.application.port.out;

import java.math.BigDecimal;

public interface EmployeePerformanceTargetClient {
    BigDecimal value(String tenant, String employee, String fromMonth, String toMonth);
}
