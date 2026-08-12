package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP 商品分类树实体；订货宝未返回父级时按根分类保存。 */
@TableName("erp_category")
public class CategoryEntity {
    /** ERP 分类主键，UUID 字符串。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键，所有查询和写入必须带此条件。 */
    public String tenantId;
    /** 父分类 ERP UUID；根分类为空。 */
    public String parentId;
    /** 订货宝父分类来源 ID。 */
    public String sourceParentId;
    /** ERP 租户内唯一分类编码。 */
    public String categoryCode;
    /** 订货宝分类编码。 */
    public String sourceCategoryNumber;
    /** 分类名称。 */
    public String name;
    /** 分类层级，根级为 1。 */
    public Integer categoryLevel;
    /** 同级分类展示顺序。 */
    public Integer sortOrder;
    /** 订货宝默认分类标记。 */
    public Boolean sourceDefaultFlag;
    /** ERP 分类状态。 */
    public String status;
    /** 数据主权状态。 */
    public String ownershipState;
    /** 记录来源。 */
    public String recordOrigin;
    /** 尚未标准化的扩展属性 JSON。 */
    public String attributesJson;
    /** 乐观版本号。 */
    public Long version;
    /** 创建用户或服务 UUID。 */
    public String createdBy;
    /** 最近更新用户或服务 UUID。 */
    public String updatedBy;
    /** ERP 记录创建时间，UTC。 */
    public LocalDateTime createdAt;
    /** ERP 记录最近更新时间，UTC。 */
    public LocalDateTime updatedAt;
}
