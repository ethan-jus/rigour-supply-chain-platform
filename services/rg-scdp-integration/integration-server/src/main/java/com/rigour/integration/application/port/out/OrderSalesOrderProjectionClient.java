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
import java.util.Optional;

/** Integration向Order投影自研销售订单、发货、回款、退款和资金单据的出站端口；实现只能调用Order公开API。 */
public interface OrderSalesOrderProjectionClient {
    default com.rigour.order.api.v1.model.HistorySyncModels.Intake cancelSourceOrder(CallerIdentity caller,
            com.rigour.order.api.v1.model.HistorySyncModels.CancelSourceOrder command) {
        throw new UnsupportedOperationException("来源取消接口未实现");
    }

    default com.rigour.order.api.v1.model.HistorySyncModels.Intake registerSourceOrder(CallerIdentity caller,
            com.rigour.order.api.v1.model.HistorySyncModels.SourceOrder command) {
        throw new UnsupportedOperationException("必须先部署历史订单保护接口");
    }
    default com.rigour.order.api.v1.model.HistorySyncModels.Intake registerReceipt(CallerIdentity caller,
            com.rigour.order.api.v1.model.HistorySyncModels.Receipt command) {
        throw new UnsupportedOperationException("必须先部署回款接续接口");
    }


    SalesOrderDetailView salesOrder(CallerIdentity caller, Long id);

    Optional<SalesOrderDetailView> findSalesOrderBySource(
            CallerIdentity caller, String sourceSystemCode, String sourceOrderNo);

    SalesOrderDetailView createSalesOrder(CallerIdentity caller, SalesOrderCommand command);

    SalesOrderDetailView updateSalesOrder(CallerIdentity caller, Long id, SalesOrderCommand command);

    SalesOrderDetailView updateSalesOrderSourceStatus(
            CallerIdentity caller, Long id, SalesOrderSourceStatusCommand command);

    SalesOrderDetailView updateSalesOrderSourceProjection(
            CallerIdentity caller, Long id, SalesOrderSourceProjectionCommand command);

    SalesOrderDetailView cancelSalesOrder(CallerIdentity caller, Long id, int revision);

    SalesOrderDetailView cancelSalesOrderBySource(CallerIdentity caller, Long id, int revision);

    SalesPaymentRecordDetailView salesPayment(CallerIdentity caller, Long id);

    Optional<SalesPaymentRecordDetailView> findSalesPaymentBySource(
            CallerIdentity caller, String sourceSystemCode, String sourceDocumentNo);

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
