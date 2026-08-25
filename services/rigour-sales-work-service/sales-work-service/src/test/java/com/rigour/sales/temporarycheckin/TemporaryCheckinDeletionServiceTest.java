package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.sales.temporarycheckin.TemporaryCheckinDeletionRepository.DeletionCandidateRow;
import com.rigour.sales.temporarycheckin.TemporaryCheckinGovernanceModels.SubmissionDeletionRequest;
import com.rigour.shared.file.FileStorage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class TemporaryCheckinDeletionServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SUBMISSION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID REQUEST_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-08-25T04:00:00Z");

    private TemporaryCheckinDeletionRepository repository;
    private FileStorage fileStorage;
    private TemporaryCheckinDeletionService service;

    @BeforeEach
    void setUp() {
        repository = mock(TemporaryCheckinDeletionRepository.class);
        fileStorage = mock(FileStorage.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        TemporaryCheckinProperties properties = new TemporaryCheckinProperties();
        properties.setTenantId(TENANT_ID.toString());
        service = new TemporaryCheckinDeletionService(repository, fileStorage,
                JsonMapper.builder().findAndAddModules().build(), transactions,
                Clock.fixed(NOW, ZoneOffset.UTC), properties);
        when(repository.findJob(TENANT_ID, REQUEST_ID)).thenReturn(Optional.empty());
        when(repository.markJobProcessing(TENANT_ID, REQUEST_ID, NOW)).thenReturn(1);
        when(repository.markPending(TENANT_ID, SUBMISSION_ID, NOW)).thenReturn(1);
    }

    @Test
    void deletesOnlyExactOwnedObjectsAndThenHardDeletesRow() {
        String photo = TENANT_ID + "/temporary-sales-checkin/" + SUBMISSION_ID
                + "/photos/storefront/abc.jpg";
        DeletionCandidateRow candidate = new DeletionCandidateRow(
                SUBMISSION_ID, "北京", "NONE", photo, null, null);
        when(repository.findCandidates(TENANT_ID, List.of(SUBMISSION_ID), "北京"))
                .thenReturn(List.of(candidate));
        when(repository.hardDelete(TENANT_ID, SUBMISSION_ID)).thenReturn(1);

        var result = service.delete("city-beijing", "北京", request());

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.deletedCount()).isEqualTo(1);
        verify(fileStorage).delete(TENANT_ID.toString(), photo);
        verify(repository).hardDelete(TENANT_ID, SUBMISSION_ID);
        verify(repository).finishJob(eq(TENANT_ID), eq(REQUEST_ID), eq("COMPLETED"),
                eq(1), eq(0), any(), eq(NOW));
    }

    @Test
    void refusesUnexpectedObjectDirectoryAndKeepsFailedRowForRetry() {
        String foreign = TENANT_ID + "/temporary-sales-checkin/" + SUBMISSION_ID
                + "/../another-submission/secret.m4a";
        DeletionCandidateRow candidate = new DeletionCandidateRow(
                SUBMISSION_ID, "北京", "NONE", null, null, foreign);
        when(repository.findCandidates(TENANT_ID, List.of(SUBMISSION_ID), "北京"))
                .thenReturn(List.of(candidate));

        var result = service.delete("city-beijing", "北京", request());

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failures()).singleElement()
                .extracting(failure -> failure.code()).isEqualTo("DELETE_FAILED");
        verify(fileStorage, never()).delete(any(), any());
        verify(repository).markFailed(TENANT_ID, SUBMISSION_ID, NOW);
        verify(repository, never()).hardDelete(any(), any());
    }

    @Test
    void rejectsIdsOutsideCityScopeBeforeAnyObjectDeletion() {
        when(repository.findCandidates(TENANT_ID, List.of(SUBMISSION_ID), "北京"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.delete("city-beijing", "北京", request()))
                .isInstanceOf(TemporaryCheckinException.class)
                .hasMessageContaining("超出当前城市范围");

        verify(fileStorage, never()).delete(any(), any());
    }

    private SubmissionDeletionRequest request() {
        return new SubmissionDeletionRequest(
                REQUEST_ID, List.of(SUBMISSION_ID), "清理测试数据", "DELETE_SELECTED_SUBMISSIONS");
    }
}
