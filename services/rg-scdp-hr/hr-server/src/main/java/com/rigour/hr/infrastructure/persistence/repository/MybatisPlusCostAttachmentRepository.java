package com.rigour.hr.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.rigour.hr.api.v1.model.HrExpenseAttachmentView;
import com.rigour.hr.application.port.out.CostAttachmentStore;
import com.rigour.hr.infrastructure.persistence.entity.CostAttachmentEntity;
import com.rigour.hr.infrastructure.persistence.entity.HrExpenseEntity;
import com.rigour.hr.infrastructure.persistence.mapper.CostAttachmentMapper;
import com.rigour.hr.infrastructure.persistence.mapper.HrExpenseMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 与费用保存共用租户锁；附件元数据、版本与审计由调用方的事务一起提交。 */
@Repository
public class MybatisPlusCostAttachmentRepository implements CostAttachmentStore {
    private final CostAttachmentMapper attachments;
    private final HrExpenseMapper costs;

    public MybatisPlusCostAttachmentRepository(
            CostAttachmentMapper attachments, HrExpenseMapper costs) {
        this.attachments = attachments;
        this.costs = costs;
    }

    public void lock(String tenant) {
        costs.ensureGuard(tenant);
        costs.lock(tenant);
    }

    private LambdaQueryWrapper<CostAttachmentEntity> scope(String tenant, String entry) {
        return new LambdaQueryWrapper<CostAttachmentEntity>()
                .eq(CostAttachmentEntity::getTenantId, tenant)
                .eq(CostAttachmentEntity::getEntryId, entry);
    }

    public List<HrExpenseAttachmentView> list(String tenant, String entry) {
        return attachments
                .selectList(
                        scope(tenant, entry)
                                .orderByAsc(
                                        CostAttachmentEntity::getCreatedTime,
                                        CostAttachmentEntity::getId))
                .stream()
                .map(
                        r ->
                                new HrExpenseAttachmentView(
                                        r.getId(),
                                        r.getFileName(),
                                        r.getContentType(),
                                        r.getFileSize(),
                                        r.getCreatedBy(),
                                        r.getCreatedTime().toInstant(ZoneOffset.UTC)))
                .toList();
    }

    public String fileKey(String tenant, String entry, String id) {
        var row = attachments.selectOne(scope(tenant, entry).eq(CostAttachmentEntity::getId, id));
        if (row == null) throw new BusinessException(ErrorCode.NOT_FOUND, "附件不存在", List.of());
        return row.getFileKey();
    }

    public void add(
            String tenant, String entry, int revision, HrExpenseAttachmentView file, String key) {
        int changed =
                costs.update(
                        null,
                        new LambdaUpdateWrapper<HrExpenseEntity>()
                                .eq(HrExpenseEntity::getTenantId, tenant)
                                .eq(HrExpenseEntity::getId, entry)
                                .eq(HrExpenseEntity::getRevision, revision)
                                .eq(HrExpenseEntity::getStatusCode, "DRAFT")
                                .set(HrExpenseEntity::getUpdatedBy, file.createdBy())
                                .set(
                                        HrExpenseEntity::getUpdatedTime,
                                        LocalDateTime.now(ZoneOffset.UTC))
                                .setSql("revision=revision+1"));
        if (changed != 1) throw new BusinessException(ErrorCode.CONFLICT, "费用已被修改，请刷新", List.of());
        var row = new CostAttachmentEntity();
        row.setId(file.id());
        row.setTenantId(tenant);
        row.setEntryId(entry);
        row.setFileKey(key);
        row.setFileName(file.fileName());
        row.setContentType(file.contentType());
        row.setFileSize(file.size());
        row.setCreatedBy(file.createdBy());
        row.setCreatedTime(LocalDateTime.ofInstant(file.createdTime(), ZoneOffset.UTC));
        attachments.insert(row);
    }
}
