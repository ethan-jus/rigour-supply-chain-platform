package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP 商品主数据同步批次实体。 */
@TableName("erp_master_data_sync_run")
public class MasterDataSyncRunEntity {
    /** ERP 同步批次主键，UUID 字符串。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键。 */
    public String tenantId;
    /** Integration 订货宝连接器 UUID。 */
    public String connectorId;
    /** 来源系统编码，例如 DINGHUOBAO。 */
    public String sourceSystem;
    /** 同步对象类型。 */
    public String objectType;
    /** 触发方式：MANUAL、SCHEDULED 或 RETRY。 */
    public String triggerType;
    /** 批次状态：RUNNING、SUCCEEDED、SUCCEEDED_WITH_WARNINGS 或 FAILED。 */
    public String status;
    /** 预留的来源增量窗口起点；一期手动全量同步为空。 */
    public LocalDateTime windowFrom;
    /** 预留的来源增量窗口终点；一期手动全量同步为空。 */
    public LocalDateTime windowTo;
    /** 允许读取的最大分页数。 */
    public Integer maxPages;
    /** 单次来源请求的分页大小。 */
    public Integer pageSize;
    /** Integration 返回并交给 ERP 导入的根对象数量。 */
    public Long fetchedCount;
    /** 首次创建的 ERP 记录数量。 */
    public Long createdCount;
    /** 来源摘要变化并更新的 ERP 记录数量。 */
    public Long changedCount;
    /** 来源摘要未变化的重复记录数量。 */
    public Long duplicateCount;
    /** 缺少必要字段而拒绝落库的记录数量。 */
    public Long rejectedCount;
    /** 已落库但尚未找到唯一有效字典映射的来源枚举出现次数。 */
    public Long unmappedCount;
    /** 本批次使用的字典编码及内容版本 JSON。 */
    public String dictSnapshotJson;
    /** 按字典、字段和来源值聚合的未映射项 JSON。 */
    public String mappingIssuesJson;
    /** 脱敏后的稳定错误码。 */
    public String errorCode;
    /** 脱敏后的失败说明，禁止写入 Token、密码和原始请求体。 */
    public String errorMessage;
    /** 批次开始时间，UTC。 */
    public LocalDateTime startedAt;
    /** 批次结束时间，UTC；运行中为空。 */
    public LocalDateTime finishedAt;
    /** 触发同步的用户或服务 UUID。 */
    public String createdBy;
    /** 批次记录创建时间，UTC。 */
    public LocalDateTime createdAt;
    /** 批次记录最近更新时间，UTC。 */
    public LocalDateTime updatedAt;
}
