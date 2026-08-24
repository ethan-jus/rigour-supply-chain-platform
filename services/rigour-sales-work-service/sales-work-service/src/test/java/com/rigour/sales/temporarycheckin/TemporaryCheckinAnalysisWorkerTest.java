package com.rigour.sales.temporarycheckin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.sales.infrastructure.storage.CosRecordingFileStorage;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAiClient.AsrState;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.MediaReference;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.TranscriptionJob;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URL;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TemporaryCheckinAnalysisWorkerTest {

    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SUBMISSION_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    private TemporaryCheckinRepository repository;
    private TemporaryCheckinAiClient aiClient;
    private CosRecordingFileStorage cosStorage;
    private TemporaryCheckinAnalysisWorker worker;

    @BeforeEach
    void setUp() {
        repository = mock(TemporaryCheckinRepository.class);
        aiClient = mock(TemporaryCheckinAiClient.class);
        cosStorage = mock(CosRecordingFileStorage.class);
        TemporaryCheckinProperties properties = new TemporaryCheckinProperties();
        properties.setTenantId(TENANT_ID.toString());
        worker = new TemporaryCheckinAnalysisWorker(repository, aiClient, cosStorage, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void submitsPollsAndSummarizesWithoutPersistingTemporaryCosUrl() throws Exception {
        TranscriptionJob pending = job("PENDING", null, null, "NOT_REQUESTED", null, "audio/mp4", 0);
        TranscriptionJob processing = job("PROCESSING", "987654", null, "NOT_REQUESTED", null,
                "audio/mp4", 1);
        TranscriptionJob summary = job("SUCCEEDED", "987654", "客户需要十套设备", "PENDING", null,
                "audio/mp4", 1);
        when(repository.findPendingTranscription(TENANT_ID)).thenReturn(Optional.of(pending));
        when(repository.claimPendingTranscription(TENANT_ID, SUBMISSION_ID, NOW)).thenReturn(1);
        when(cosStorage.generatePresignedGetUrl(TENANT_ID.toString(), pending.audio().objectKey()))
                .thenReturn(new URL("https://private.example.test/audio.m4a?signature=secret"));
        when(aiClient.createTranscriptionTask(any(URI.class)))
                .thenReturn(new TemporaryCheckinAiClient.AsrTask("987654", "create-request"));
        when(repository.findProcessingTranscriptions(eq(TENANT_ID), any(Instant.class), eq(5)))
                .thenReturn(List.of(processing));
        when(aiClient.describeTranscriptionTask("987654"))
                .thenReturn(new TemporaryCheckinAiClient.AsrTaskStatus(
                        "987654", AsrState.SUCCEEDED, "客户需要十套设备", null,
                        "describe-request", new BigDecimal("8.5")));
        when(repository.findPendingSummary(TENANT_ID)).thenReturn(Optional.of(summary));
        when(repository.claimPendingSummary(TENANT_ID, SUBMISSION_ID, NOW)).thenReturn(1);
        when(aiClient.summarize("客户需要十套设备"))
                .thenReturn(new TemporaryCheckinAiClient.SummaryResult(
                        "客户需求：十套设备", "hunyuan-turbos-latest", "summary-request"));

        worker.process();

        verify(repository).markTranscriptionProcessing(
                TENANT_ID, SUBMISSION_ID, "987654", "create-request", NOW);
        verify(repository).markTranscriptionSucceeded(
                TENANT_ID, SUBMISSION_ID, "客户需要十套设备", NOW);
        verify(repository).markSummarySucceeded(
                TENANT_ID, SUBMISSION_ID, "客户需求：十套设备", "hunyuan-turbos-latest", NOW);
    }

    @Test
    void marksWebmUnsupportedWithoutSendingAudioOutsideCos() {
        TranscriptionJob pending = job("PENDING", null, null, "NOT_REQUESTED", null, "audio/webm", 0);
        when(repository.findPendingTranscription(TENANT_ID)).thenReturn(Optional.of(pending));
        when(repository.findProcessingTranscriptions(eq(TENANT_ID), any(Instant.class), eq(5)))
                .thenReturn(List.of());
        when(repository.findPendingSummary(TENANT_ID)).thenReturn(Optional.empty());

        worker.process();

        verify(repository).markTranscriptionUnsupported(
                TENANT_ID, SUBMISSION_ID, "PENDING", "AUDIO_FORMAT_UNSUPPORTED", NOW);
        verify(cosStorage, never()).generatePresignedGetUrl(any(), any());
        verify(aiClient, never()).createTranscriptionTask(any());
    }

    private static TranscriptionJob job(
            String transcriptionStatus,
            String taskId,
            String transcript,
            String summaryStatus,
            String summary,
            String contentType,
            int attempts) {
        MediaReference audio = new MediaReference(
                TENANT_ID + "/temporary-sales-checkin/" + SUBMISSION_ID + "/audio/content.m4a",
                contentType, 1024L, "a".repeat(64), "visit.m4a", null, null, null);
        return new TranscriptionJob(SUBMISSION_ID, audio, transcriptionStatus, taskId, null,
                transcript, null, attempts, NOW, summaryStatus, summary, null, null, NOW);
    }
}
