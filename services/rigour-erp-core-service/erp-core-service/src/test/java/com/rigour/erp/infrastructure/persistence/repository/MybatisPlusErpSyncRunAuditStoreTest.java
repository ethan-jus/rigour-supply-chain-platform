package com.rigour.erp.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.rigour.erp.application.port.out.ErpSyncRunAuditStore.ScheduledSkipReason;
import com.rigour.erp.infrastructure.persistence.entity.MasterDataSyncRunEntity;
import com.rigour.erp.infrastructure.persistence.mapper.MasterDataSyncRunMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** ERP 定时调度跳过审计的持久化约束测试。 */
class MybatisPlusErpSyncRunAuditStoreTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb200-0000-7000-8000-000000000001");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb200-0000-7000-8000-000000000002");
    private static final UUID TASK_ID = UUID.fromString("019fb200-0000-7000-8000-000000000003");
    private static final Instant NOW = Instant.parse("2026-08-17T04:30:00Z");

    @Test
    void persistsSkippedAsATerminalTraceableRunWithStableReason() {
        MasterDataSyncRunMapper mapper = mock(MasterDataSyncRunMapper.class);
        var store = new MybatisPlusErpSyncRunAuditStore(
                mapper, Clock.fixed(NOW, ZoneOffset.UTC));

        UUID runId = store.recordScheduledSkip(TENANT_ID, CONNECTOR_ID, TASK_ID,
                "PRODUCT_SPU", 100, ScheduledSkipReason.CONNECTOR_LEASE_CONFLICT);

        ArgumentCaptor<MasterDataSyncRunEntity> captor =
                ArgumentCaptor.forClass(MasterDataSyncRunEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue()).satisfies(entity -> {
            assertThat(entity.id).isEqualTo(runId.toString());
            assertThat(entity.tenantId).isEqualTo(TENANT_ID.toString());
            assertThat(entity.connectorId).isEqualTo(CONNECTOR_ID.toString());
            assertThat(entity.sourceTaskId).isEqualTo(TASK_ID.toString());
            assertThat(entity.sourceSystem).isEqualTo("DINGHUOBAO");
            assertThat(entity.objectType).isEqualTo("PRODUCT_SPU");
            assertThat(entity.triggerType).isEqualTo("SCHEDULED");
            assertThat(entity.status).isEqualTo("SKIPPED");
            assertThat(entity.maxPages).isEqualTo(100);
            assertThat(entity.pageSize).isZero();
            assertThat(entity.fetchedCount).isZero();
            assertThat(entity.createdCount).isZero();
            assertThat(entity.changedCount).isZero();
            assertThat(entity.duplicateCount).isZero();
            assertThat(entity.rejectedCount).isZero();
            assertThat(entity.unmappedCount).isZero();
            assertThat(entity.errorCode).isEqualTo("CONNECTOR_LEASE_CONFLICT");
            assertThat(entity.errorMessage)
                    .isEqualTo("连接器租约在ERP业务动作开始前已被占用，本对象本轮跳过")
                    .doesNotContain("token", "password", "connector busy");
            assertThat(entity.startedAt).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
            assertThat(entity.finishedAt).isEqualTo(entity.startedAt);
            assertThat(entity.createdTime).isEqualTo(entity.startedAt);
            assertThat(entity.updatedTime).isEqualTo(entity.startedAt);
        });
    }

    @Test
    void rejectsUnboundedObjectTypeBeforeWriting() {
        MasterDataSyncRunMapper mapper = mock(MasterDataSyncRunMapper.class);
        var store = new MybatisPlusErpSyncRunAuditStore(mapper, Clock.systemUTC());

        assertThatThrownBy(() -> store.recordScheduledSkip(TENANT_ID, CONNECTOR_ID, TASK_ID,
                "product master data", 100, ScheduledSkipReason.OBJECT_SYNC_LOCK_CONFLICT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1到32位大写业务编码");
    }
}
