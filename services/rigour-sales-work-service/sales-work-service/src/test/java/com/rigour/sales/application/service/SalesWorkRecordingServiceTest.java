package com.rigour.sales.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.sales.api.v1.model.SalesWorkApiModels.DiscardRecordingClipCommand;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository.IdentityProjection;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository.SalesProfile;
import com.rigour.sales.application.port.out.SalesWorkRecordingRepository;
import com.rigour.sales.application.port.out.SalesWorkVisitRepository;
import com.rigour.sales.application.port.out.SalesWorkVisitRepository.VisitSnapshot;
import com.rigour.sales.infrastructure.config.SalesRecordingProperties;
import com.rigour.shared.audit.AuditEvent;
import com.rigour.shared.audit.AuditSink;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.file.FileStorage;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** 不依赖容器验证短录音最小化存储和服务端防绕过。 */
class SalesWorkRecordingServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();
    private final UUID visitId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-08-09T03:00:00Z");
    private SalesWorkRecordingService service;
    private SalesWorkRecordingRepository recordingRepository;
    private FileStorage fileStorage;
    private AuditSink auditSink;

    @BeforeEach
    void setUp() throws Exception {
        recordingRepository = mock(SalesWorkRecordingRepository.class);
        SalesWorkVisitRepository visitRepository = mock(SalesWorkVisitRepository.class);
        SalesWorkQueryRepository queryRepository = mock(SalesWorkQueryRepository.class);
        SalesWorkContextService contextService = mock(SalesWorkContextService.class);
        fileStorage = mock(FileStorage.class);
        auditSink = mock(AuditSink.class);
        SalesRecordingProperties properties = new SalesRecordingProperties();
        properties.setMinimumClipSeconds(30);

        CallerIdentity caller = new CallerIdentity("TENANT", userId, tenantId, userId, null,
                UUID.randomUUID(), 1, 1, 1, Set.of("SALES"),
                Set.of("sales:recording:own:write"));
        Method set = AuthorizationContext.class.getDeclaredMethod("set", CallerIdentity.class);
        set.setAccessible(true);
        set.invoke(null, caller);
        RequestContext.set("recording-unit-test", "zh-CN");

        var identity = new SalesWorkContextService.SalesIdentity(
                new IdentityProjection(userId, UUID.randomUUID(), "ACTIVE"),
                new SalesProfile(profileId, UUID.randomUUID(), "S-001", null, "ACTIVE"));
        when(contextService.resolveIdentity(any(), any())).thenReturn(identity);
        when(recordingRepository.findDiscardByClientId(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(visitRepository.findVisit(tenantId, profileId, visitId)).thenReturn(Optional.of(
                new VisitSnapshot(visitId, UUID.randomUUID(), profileId, "MY_STORE",
                        UUID.randomUUID(), UUID.randomUUID(), "CHECKED_IN",
                        now.minusSeconds(120), null, UUID.randomUUID(), now.minusSeconds(120),
                        null, null, null, null, null, null, null, null, null)));

        service = new SalesWorkRecordingService(recordingRepository, visitRepository, queryRepository,
                contextService, fileStorage, properties, auditSink,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() throws Exception {
        Method clear = AuthorizationContext.class.getDeclaredMethod("clear");
        clear.setAccessible(true);
        clear.invoke(null);
        RequestContext.clear();
    }

    @Test
    void uploadBelowThirtySecondsIsRejectedBeforeObjectStorage() {
        Instant recordedTo = now.minusSeconds(10);
        var file = new MockMultipartFile("file", "short.aac", "audio/aac", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.uploadClip(visitId, file, "short-upload", 20_000L,
                recordedTo.minusSeconds(20), recordedTo))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_RECORDING_INVALID);
                    assertThat(error.getMessage()).contains("不足 30 秒");
                });

        verify(fileStorage, never()).put(any(), any());
        verify(auditSink, never()).append(any(AuditEvent.class));
    }

    @Test
    void discardedShortClipOnlyWritesAuditMetadata() {
        Instant recordedTo = now.minusSeconds(10);
        var result = service.discardClip(visitId, new DiscardRecordingClipCommand(
                "short-discard", 20_000L, recordedTo.minusSeconds(20), recordedTo, "TOO_SHORT"));

        assertThat(result.disposition()).isEqualTo("DISCARDED_NOT_STORED");
        verify(fileStorage, never()).put(any(), any());
        verify(recordingRepository).insertDiscard(any(), any(), any(), any(),
                anyLong(), any(), any(), any(), any());
        verify(auditSink).append(any(AuditEvent.class));
    }
}
