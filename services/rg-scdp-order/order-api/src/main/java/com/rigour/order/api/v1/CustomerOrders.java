package com.rigour.order.api.v1;
import java.math.BigDecimal;
import java.util.List;
public record CustomerOrders(long total,BigDecimal amount,BigDecimal unpaid,List<OrderWorkbenchApi.Row> items) {}
