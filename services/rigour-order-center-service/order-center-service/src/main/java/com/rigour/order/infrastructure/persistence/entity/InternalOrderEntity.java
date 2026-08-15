package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 内部订单主表；source_* 字段只描述外部来源事实，internal_status 属于自研订单流程。 */
@TableName("order_order")
public class InternalOrderEntity {
    /** 平台内部订单主键，UUID字符串。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键，所有SQL查询必须带此条件。 */
    public String tenantId;
    /** 平台订单号；一期订货宝导入默认与sourceOrderNo一致。 */
    public String orderNo;
    /** 来源系统编码。 */
    public String sourceSystem;
    /** 来源系统订单号，和租户、来源系统组成幂等键。 */
    public String sourceOrderNo;
    /** 内部订单流程状态；外部同步不得覆盖。 */
    public String internalStatus;
    /** 来源订单状态原值。 */
    public String sourceStatus;
    /** 来源支付状态原值。 */
    public String paymentStatus;
    /** 来源订单类型。 */
    public String orderType;
    /** 订单总金额。 */
    public BigDecimal totalAmount;
    /** 来源下单时间。 */
    public LocalDateTime orderedAt;
    /** 来源最后更新时间，用于增量同步。 */
    public LocalDateTime sourceUpdatedAt;
    /** 来源更新时间字段原值。 */
    public String sourceUpdateTime;
    /** 来源要求交付日期。 */
    public String deliveryDate;
    /** 订单备注。 */
    public String remark;
    /** 来源客户编号。 */
    public String sourceCustomerNo;
    /** 来源客户GUID。 */
    public String sourceCustomerGuid;
    /** 客户名称快照。 */
    public String customerName;
    /** 收货人快照。 */
    public String receiverName;
    /** 收货单位快照。 */
    public String receiverCompany;
    /** 收货电话，敏感字段。 */
    public String receiverPhone;
    /** 收货地址，敏感字段。 */
    public String receiverAddress;
    /** 收货省份。 */
    public String province;
    /** 收货城市。 */
    public String city;
    /** 收货区县。 */
    public String district;
    /** 来源接口状态。 */
    public String sourceApiStatus;
    /** 来源异常状态。 */
    public String sourceExceptionStatus;
    /** 来源发货方式。 */
    public String sourceSendType;
    /** 来源最后下单时间原值。 */
    public String sourceLastOrderAt;
    /** 来源设备。 */
    public String sourceDevice;
    /** 是否管理员订单原值。 */
    public String sourceAdminOrder;
    /** 来源拆单类型。 */
    public String splitType;
    /** 来源拆单类型名称。 */
    public String splitTypeName;
    /** 最近一次导入报文SHA-256。 */
    public String sourcePayloadHash;
    /** 明细最近同步时间，为空表示尚未获取明细。 */
    public LocalDateTime detailSyncedAt;
    /** 首次导入时间。 */
    public LocalDateTime importedAt;
    /** 最近同步成功时间。 */
    public LocalDateTime syncedAt;
    /** 订货宝详情补充字段。 */
    public String customerType;
    public String customerArea;
    public String adminUser;
    public String operationName;
    public String salesPerson;
    public String salesPersonMobile;
    public String assistantSalesPersons;
    public String auditAt;
    public String settlementMethod;
    public BigDecimal goodsWeight;
    public BigDecimal taxAmount;
    public BigDecimal discountPrice;
    public BigDecimal discountTotal;
    public BigDecimal freightAmount;
    public BigDecimal applyTotal;
    public BigDecimal couponDiscountedAmount;
    public String customerRemark;
    public String internalComment;
    public String invoiceTitle;
    public String invoiceContent;
    public String invoiceBank;
    public String invoiceBankAccount;
    public String taxpayerNumber;
    /** 订货宝详情扩展字段。 */
    public String customerTag;
    public String invoiceType;
    /** 内部乐观版本号。 */
    public Long version;
    /** 记录创建时间。 */
    public LocalDateTime createdAt;
    /** 记录最后更新时间。 */
    public LocalDateTime updatedAt;
}
