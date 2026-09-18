package com.rigour.order.api.v1.model;
import java.util.List;
import java.math.BigDecimal;
/** 仓管履约视图；不返回售价、回款和销售经营信息。 */
public record OrderFulfillmentQueueView(long total,int begin,int step,List<Item> items) {
 public record Item(String orderId,String orderNo,String warehouseId,String warehouseName,String outboundStatus,int revision) {}
 public record Line(String productCode,String skuCode,String productName,String unitCode,BigDecimal quantity) {}
 public record Detail(Item order,OrderFulfillmentStatusView execution,List<Line> lines) {}
}
