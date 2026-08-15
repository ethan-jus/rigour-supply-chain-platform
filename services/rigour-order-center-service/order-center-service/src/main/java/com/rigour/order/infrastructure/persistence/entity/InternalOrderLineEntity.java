package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("order_order_line")
public class InternalOrderLineEntity {
    /** 平台内部明细主键。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 关联order_order.id。 */
    public String orderId;
    /** 来源明细ID，和order_id组成幂等键。 */
    public String sourceLineId;
    /** 来源商品GUID。 */
    public String sourceProductGuid;
    /** 来源SKU编号。 */
    public String skuNo;
    /** 订货宝商品选项编号。 */
    public String sourceOptionsGoodsNo;
    /** 订货宝商品条码。 */
    public String sourceBarcode;
    /** 商品名称快照。 */
    public String productName;
    /** 来源商品编码，后续映射ERP商品编码。 */
    public String productCode;
    /** 第一层规格。 */
    public String specificationFirst;
    /** 第二层规格。 */
    public String specificationSecond;
    /** 规格组合名称。 */
    public String specificationName;
    /** 来源单价。 */
    public BigDecimal unitPrice;
    /** 订购数量。 */
    public BigDecimal quantity;
    /** 明细金额。 */
    public BigDecimal lineAmount;
    /** 计量单位。 */
    public String unit;
    /** 明细备注。 */
    public String remark;
    /** 订货宝详情补充字段。 */
    public BigDecimal purchasePrice;
    public BigDecimal conversionNumber;
    public BigDecimal offerPrice;
    public BigDecimal actualAmount;
    public BigDecimal goodsWeight;
    public String preSale;
    public String contentType;
    public String invoiceTax;
    public BigDecimal contentPercent;
    /** 创建时间。 */
    public LocalDateTime createdAt;
    /** 最后更新时间。 */
    public LocalDateTime updatedAt;
}
