package com.rigour.integration.application.port.out;

import com.rigour.order.api.v1.model.SalesOrderCommand;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.FundDocumentCommand;
import com.rigour.order.api.v1.model.FundDocumentDetailView;
import com.rigour.order.api.v1.model.SalesPaymentRecordCommand;
import com.rigour.order.api.v1.model.SalesPaymentRecordDetailView;
import com.rigour.order.api.v1.model.SalesRefundRecordCommand;
import com.rigour.order.api.v1.model.SalesRefundRecordDetailView;
import com.rigour.order.api.v1.model.SalesShipmentCommand;
import com.rigour.order.api.v1.model.SalesShipmentDetailView;
import com.rigour.order.api.v1.model.SalesOrderSourceProjectionCommand;
import com.rigour.order.api.v1.model.SalesOrderSourceStatusCommand;
import com.rigour.shared.context.CallerIdentity;

/** Integration向Order投影自研销售订单、发货、回款、退款和资金单据的出站端口；实现只能调用Order公开API。 */
public interface OrderSalesOrderProjectionClient {

    SalesOrderDetailView salesOrder(CallerIdentity caller, Long id);

    SalesOrderDetailView createSalesOrder(CallerIdentity caller, SalesOrderCommand command);

    SalesOrderDetailView updateSalesOrder(CallerIdentity caller, Long id, SalesOrderCommand command);

    SalesOrderDetailView updateSalesOrderSourceStatus(
            CallerIdentity caller, Long id, SalesOrderSourceStatusCommand command);

    SalesOrderDetailView updateSalesOrderSourceProjection(
            CallerIdentity caller, Long id, SalesOrderSourceProjectionCommand command);

    SalesOrderDetailView cancelSalesOrder(CallerIdentity caller, Long id, int revision);

    SalesOrderDetailView cancelSalesOrderBySource(CallerIdentity caller, Long id, int revision);

    SalesPaymentRecordDetailView salesPayment(CallerIdentity caller, Long id);

    SalesPaymentRecordDetailView createSalesPayment(CallerIdentity caller, SalesPaymentRecordCommand command);

    SalesPaymentRecordDetailView updateSalesPayment(CallerIdentity caller, Long id,
                                                    SalesPaymentRecordCommand command);

    FundDocumentDetailView fundDocument(CallerIdentity caller, Long id);

    FundDocumentDetailView createFundDocument(CallerIdentity caller, FundDocumentCommand command);

    FundDocumentDetailView updateFundDocument(CallerIdentity caller, Long id,
                                              FundDocumentCommand command);

    SalesRefundRecordDetailView salesRefund(CallerIdentity caller, Long id);

    SalesRefundRecordDetailView createSalesRefund(CallerIdentity caller, SalesRefundRecordCommand command);

    SalesRefundRecordDetailView updateSalesRefund(CallerIdentity caller, Long id,
                                                  SalesRefundRecordCommand command);

    SalesShipmentDetailView salesShipment(CallerIdentity caller, Long id);

    SalesShipmentDetailView createSalesShipment(CallerIdentity caller, SalesShipmentCommand command);

    SalesShipmentDetailView updateSalesShipment(CallerIdentity caller, Long id, SalesShipmentCommand command);
}
