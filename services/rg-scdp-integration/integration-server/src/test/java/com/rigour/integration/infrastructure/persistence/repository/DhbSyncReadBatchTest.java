package com.rigour.integration.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.integration.api.v1.model.DhbSyncExceptionView;
import com.rigour.integration.infrastructure.persistence.IntegrationUuidCodec;
import com.rigour.integration.infrastructure.persistence.entity.*;
import com.rigour.integration.infrastructure.persistence.mapper.*;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;

class DhbSyncReadBatchTest {
    final UUID tenant = UUID.randomUUID(), taskId = UUID.randomUUID();
    final IntegrationSyncTaskMapper tasks = mock(IntegrationSyncTaskMapper.class);
    final IntegrationSyncRunMapper runs = mock(IntegrationSyncRunMapper.class);
    final IntegrationReconciliationCaseMapper cases =
            mock(IntegrationReconciliationCaseMapper.class);
    final MybatisPlusDhbIntegrationStore store =
            new MybatisPlusDhbIntegrationStore(
                    mock(DhbConnectorMapper.class),
                    tasks,
                    mock(IntegrationFieldMappingMapper.class),
                    mock(IntegrationOrderMirrorMapper.class),
                    mock(IntegrationSyncLogMapper.class),
                    mock(ExternalObjectMappingMapper.class),
                    runs,
                    mock(IntegrationDeadLetterMapper.class),
                    cases,
                    mock(IntegrationManualResolutionMapper.class),
                    mock(IntegrationRawLandingMapper.class),
                    mock(PlatformTransactionManager.class),
                    JsonMapper.builder().build());

    @Test
    void fiveHundredRunsShareOneTaskLookupAndRetainDeletedTaskHistory() {
        List<IntegrationSyncRunEntity> rows = new ArrayList<>();
        for (int i = 0; i < 500; i++) rows.add(run());
        var task = task();
        task.deletedAt = java.time.LocalDateTime.now();
        when(runs.selectList(any(QueryWrapper.class))).thenReturn(rows);
        when(tasks.selectList(any(QueryWrapper.class))).thenReturn(List.of(task));
        assertThat(store.syncRuns(tenant, "ORDER", null, 500)).hasSize(500);
        verify(tasks, times(1)).selectList(any(QueryWrapper.class));
        verify(tasks, never()).selectOne(any());
        var query = capturedTasks();
        assertThat(query.getSqlSegment())
                .contains("tenant_id", "id IN")
                .doesNotContain("deleted_at");
        assertThat(query.getParamNameValuePairs().values())
                .anySatisfy(v -> assertThat((byte[]) v).isEqualTo(bin(tenant)));
    }

    @Test
    void exactOldRunIdsAvoidLatestRunLimitAndEmptyIdsDoNotRead() {
        assertThat(store.syncRunsByIds(tenant, Set.of())).isEmpty();
        verifyNoInteractions(runs, tasks);
        var row = run();
        when(runs.selectList(any(QueryWrapper.class))).thenReturn(List.of(row));
        when(tasks.selectList(any(QueryWrapper.class))).thenReturn(List.of(task()));
        assertThat(store.syncRunsByIds(tenant, Set.of(IntegrationUuidCodec.decode(row.id))))
                .hasSize(1);
        ArgumentCaptor<QueryWrapper> q = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(runs).selectList(q.capture());
        assertThat(q.getValue().getSqlSegment())
                .contains("tenant_id", "id IN")
                .doesNotContain("LIMIT");
    }

    @Test
    void evidenceUsesTenantRunTypeAndBusinessKeysWithoutGlobalTruncation() {
        var issue =
                new DhbSyncExceptionView(
                        UUID.randomUUID(),
                        tenant,
                        UUID.randomUUID(),
                        null,
                        "DHB",
                        "SALES_SHIPMENT",
                        "FH-OLD",
                        "OPEN",
                        1,
                        null,
                        "MISSING",
                        "",
                        null,
                        Instant.EPOCH,
                        Instant.EPOCH);
        store.openReconciliations(tenant, List.of(issue));
        ArgumentCaptor<QueryWrapper> q = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(cases).selectList(q.capture());
        assertThat(q.getValue().getSqlSegment())
                .contains("tenant_id", "run_id IN", "source_object_type IN", "business_key IN")
                .doesNotContain("LIMIT");
        assertThat(q.getValue().getParamNameValuePairs().values())
                .contains("OPEN", "ERROR", "SALES_SHIPMENT", "FH-OLD");
        assertThat(store.openReconciliations(tenant, List.of())).isEmpty();
        verifyNoMoreInteractions(cases);
    }

    QueryWrapper<?> capturedTasks() {
        ArgumentCaptor<QueryWrapper> q = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(tasks).selectList(q.capture());
        return q.getValue();
    }

    IntegrationSyncTaskEntity task() {
        var task = new IntegrationSyncTaskEntity();
        task.id = bin(taskId);
        task.tenantId = bin(tenant);
        task.connectorId = bin(UUID.randomUUID());
        task.taskCode = "ORDER";
        task.objectType = "ORDER";
        return task;
    }

    IntegrationSyncRunEntity run() {
        var run = new IntegrationSyncRunEntity();
        run.id = bin(UUID.randomUUID());
        run.tenantId = bin(tenant);
        run.taskId = bin(taskId);
        return run;
    }

    static byte[] bin(UUID id) {
        return IntegrationUuidCodec.encode(id);
    }
}
