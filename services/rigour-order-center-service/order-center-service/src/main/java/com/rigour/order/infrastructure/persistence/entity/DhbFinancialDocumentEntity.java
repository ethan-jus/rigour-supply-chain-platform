package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订货宝收款单和付款单只读投影。 */
@TableName("order_dhb_financial_document")
public class DhbFinancialDocumentEntity {
    /** 平台收付款投影ID，UUID。 */ @TableId(type = IdType.INPUT) public String id;
    /** 租户ID，所有查询必须带此条件。 */ public String tenantId;
    /** 来源系统，固定DINGHUOBAO。 */ public String sourceSystem;
    /** RECEIPT收款单或PAYMENT付款单。 */ public String documentType;
    /** 来源收款单或付款单编号，租户内幂等键。 */ public String documentNo;
    /** 付款关联收款单等来源关联单号。 */ public String relatedDocumentNo;
    /** 关联订货宝订单号。 */ public String orderNo;
    /** 来源客户编号。 */ public String customerNo;
    /** 客户ERP外码。 */ public String customerGuid;
    /** IncexpId：1普通充值、19预付款充值、13订单收款、8期初充值、2退货退款、10退款失败回冲、9退款红冲。 */ public String businessType;
    /** 来源支付方式兼容字段TypeId；当前官方收付款列表可能不返回，未返回时为空。 */ public String paymentMethod;
    /** 收款或付款金额。 */ public BigDecimal amount;
    /** pend_receipt待确认、pend_receipted已确认、canceled已取消；来源未返回时为空。 */ public String sourceStatus;
    /** 来源转账日期，统一存UTC。 */ public LocalDateTime transactionAt;
    /** 来源录入时间，统一存UTC。 */ public LocalDateTime sourceCreatedAt;
    /** 来源修改时间，统一存UTC。 */ public LocalDateTime sourceUpdatedAt;
    /** 来源收付款流水号。 */ public String serialNumber;
    /** 来源开户名称。 */ public String accountName;
    /** 来源开户行。 */ public String bankName;
    /** 来源银行账号，敏感字段。 */ public String accountNumber;
    /** 来源备注。 */ public String remark;
    /** 单条来源原始JSON，不含Token。 */ public String rawJson;
    /** 原始JSON的SHA-256摘要。 */ public String payloadHash;
    /** 最近一次成功同步时间。 */ public LocalDateTime syncedAt;
    /** 本地创建时间。 */ public LocalDateTime createdAt;
    /** 本地更新时间。 */ public LocalDateTime updatedAt;
}
