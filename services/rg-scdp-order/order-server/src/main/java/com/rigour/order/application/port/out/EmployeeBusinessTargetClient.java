package com.rigour.order.application.port.out;

import java.math.BigDecimal;

public interface EmployeeBusinessTargetClient {
    record Targets(BigDecimal salesTargetAmount, BigDecimal receiptTargetAmount) {}

    Targets values(String tenant, String employee, String fromMonth, String toMonth);
}
