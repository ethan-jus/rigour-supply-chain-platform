package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** 订货宝收款单或付款单本地投影。 */
public record DhbFinancialDocumentView(
        /** RECEIPT收款单或PAYMENT付款单。 */ String documentType,
        /** 收款单ReceiptsNum或付款单PaymentNum。 */ String documentNo,
        /** 付款关联收款单等来源关联单号。 */ String relatedDocumentNo,
        /** 关联订货宝订单号OrdersNum。 */ String orderNo,
        /** 来源客户编号ClientNum。 */ String customerNo,
        /** IncexpId：1普通充值、19预付款充值、13订单收款、8期初充值、2退货退款、10退款失败回冲、9退款红冲；其他值原样展示。 */ String businessType,
        /** 来源支付方式兼容字段TypeId；当前官方收付款列表可能不返回，未返回时为空。 */ String paymentMethod,
        /** 收款或付款金额Amount。 */ BigDecimal amount,
        /** pend_receipt待确认、pend_receipted已确认、canceled已取消；来源未返回时为空。 */ String status,
        /** 来源转账日期ReceiptsDate，响应为UTC Instant。 */ Instant transactionAt,
        /** 来源收付款流水号SerialNumber。 */ String serialNumber,
        /** 来源开户名称AccountName。 */ String accountName,
        /** 来源开户行BankName。 */ String bankName,
        /** 来源银行账号AccountNumber，属于敏感字段。 */ String accountNumber,
        /** 来源备注Remark。 */ String remark,
        /** 最近一次成功落库时间，UTC。 */ Instant syncedAt) {
}
