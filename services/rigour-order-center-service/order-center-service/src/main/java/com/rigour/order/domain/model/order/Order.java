package com.rigour.order.domain.model.order;

import com.rigour.order.domain.model.order.enums.OrderSourceSystem;
import com.rigour.order.domain.model.order.enums.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 平台内部订单模型。
 *
 * <p>该模型不依赖订货宝字段命名。外部系统只负责提供来源事实，内部状态由订单中心自己的订单流程维护。</p>
 */
public record Order(
        /** 平台内部订单ID，UUID格式，跨服务事件引用该值。 */
        String id,
        /** 租户ID，所有订单查询和事件都必须按租户隔离。 */
        String tenantId,
        /** 平台订单号；订货宝导入一期沿用来源订单号，后续自研单据可独立生成。 */
        String orderNo,
        /** 来源系统编码，例如DINGHUOBAO。 */
        String sourceSystem,
        /** 来源系统订单号，例如订货宝OrderSN。 */
        String sourceOrderNo,
        /** 平台内部流程状态，见OrderStatus；不会被外部同步覆盖。 */
        String internalStatus,
        /** 外部来源状态原值，便于追溯和重新映射。 */
        String sourceStatus,
        /** 来源支付状态，保留原值，后续由资金域建立统一字典。 */
        String paymentStatus,
        /** 来源订单类型。 */
        String orderType,
        /** 订单总金额；金额语义和币种规则后续由订单域统一约束。 */
        BigDecimal totalAmount,
        /** 来源下单时间。 */
        LocalDateTime orderedAt,
        /** 来源系统最后更新时间，用于增量同步和冲突判断。 */
        LocalDateTime sourceUpdatedAt,
        /** 来源更新时间字段原值，兼容订货宝展示精度和格式。 */
        String sourceUpdateTime,
        /** 来源要求的交付日期原值。 */
        String deliveryDate,
        /** 订单备注。 */
        String remark,
        /** 来源客户编码，后续映射到CRM客户。 */
        String sourceCustomerNo,
        /** 来源客户GUID，后续映射到CRM客户。 */
        String sourceCustomerGuid,
        /** 客户名称快照。 */
        String customerName,
        /** 收货人名称快照。 */
        String receiverName,
        /** 收货单位名称快照。 */
        String receiverCompany,
        /** 收货联系电话；敏感字段，日志不得输出。 */
        String receiverPhone,
        /** 收货地址快照；敏感字段，日志不得输出。 */
        String receiverAddress,
        /** 收货省份。 */
        String province,
        /** 收货城市。 */
        String city,
        /** 收货区县。 */
        String district,
        /** 来源接口状态，例如OrderApi。 */
        String sourceApiStatus,
        /** 来源异常状态，例如OrderException。 */
        String sourceExceptionStatus,
        /** 来源配送/发货方式。 */
        String sourceSendType,
        /** 来源最后下单时间原值。 */
        String sourceLastOrderAt,
        /** 来源设备。 */
        String sourceDevice,
        /** 是否管理员订单原值。 */
        String sourceAdminOrder,
        /** 来源拆单类型。 */
        String splitType,
        /** 来源拆单类型名称。 */
        String splitTypeName,
        /** 最近一次导入报文的SHA-256，用于幂等和变更检测。 */
        String sourcePayloadHash,
        /** 明细最近一次成功同步时间；为空表示只有列表摘要。 */
        LocalDateTime detailSyncedAt,
        /** 首次导入内部订单的时间。 */
        LocalDateTime importedAt,
        /** 最近一次从来源同步成功的时间。 */
        LocalDateTime syncedAt) {

    public static final String SOURCE_DINGHUOBAO = OrderSourceSystem.DINGHUOBAO.code();
    public static final String STATUS_RECEIVED = OrderStatus.RECEIVED.code();
    public static final String STATUS_CANCELLED = OrderStatus.CANCELLED.code();
    public static final String STATUS_EXCEPTION = OrderStatus.EXCEPTION.code();
}
