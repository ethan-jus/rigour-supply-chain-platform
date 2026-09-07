package com.rigour.analytics.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** BI 城市成本快照表标记实体，聚合查询通过同一个 MyBatis-Plus Mapper 执行。 */
@TableName("bi_city_cost_record")
public class SupplyDashboardSourceMarkerEntity {
    /** ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
}
