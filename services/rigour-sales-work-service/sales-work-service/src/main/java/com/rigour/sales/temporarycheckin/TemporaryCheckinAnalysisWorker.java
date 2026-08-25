package com.rigour.sales.temporarycheckin;

import com.rigour.sales.infrastructure.storage.CosRecordingFileStorage;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAiClient.AiClientException;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAiClient.AsrState;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.TranscriptionJob;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 录音异步转写与摘要的小型轮询器。只处理已确认当前隐私提示且已提交的录音，
 * 不把 COS 临时地址、录音内容、转写正文或摘要正文写入日志。
 */
@Component
@ConditionalOnProperty(
        prefix = "rigour.sales.temporary-checkin.ai",
        name = "enabled",
        havingValue = "true")
public class TemporaryCheckinAnalysisWorker {

    private static final Logger log = LoggerFactory.getLogger(TemporaryCheckinAnalysisWorker.class);
    private static final Duration STUCK_AFTER = Duration.ofMinutes(5);
    private static final Duration POLL_AFTER = Duration.ofSeconds(8);
    private static final int POLL_BATCH_SIZE = 5;
    private static final int MAX_SUBMISSION_ATTEMPTS = 5;
    private static final int MAX_TRANSCRIPT_CHARACTERS = 2_000_000;
    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            "audio/aac", "audio/aacp", "audio/amr", "audio/mp4", "audio/m4a", "audio/x-m4a",
            "audio/mpeg", "audio/mp3", "audio/ogg", "application/ogg", "audio/wav", "audio/x-wav",
            "video/mp4");

    private final TemporaryCheckinRepository repository;
    private final TemporaryCheckinAiClient aiClient;
    private final CosRecordingFileStorage cosStorage;
    private final Clock clock;
    private final UUID tenantId;

    public TemporaryCheckinAnalysisWorker(
            TemporaryCheckinRepository repository,
            TemporaryCheckinAiClient aiClient,
            CosRecordingFileStorage cosStorage,
            TemporaryCheckinProperties properties,
            Clock clock) {
        this.repository = repository;
        this.aiClient = aiClient;
        this.cosStorage = cosStorage;
        this.clock = clock;
        this.tenantId = properties.requireTenantId();
    }

    @Scheduled(
            initialDelayString = "${rigour.sales.temporary-checkin.ai.initial-delay:10s}",
            fixedDelayString = "${rigour.sales.temporary-checkin.ai.poll-interval:15s}")
    public synchronized void process() {
        Instant now = clock.instant();
        repository.recoverStuckAnalysis(tenantId, now.minus(STUCK_AFTER), now);
        submitOne(now);
        pollTasks(now);
        summarizeOne(now);
    }

    private void submitOne(Instant now) {
        TranscriptionJob job = repository.findPendingTranscription(tenantId).orElse(null);
        if (job == null) return;
        if (!isSupported(job.audio().contentType())) {
            repository.markTranscriptionUnsupported(
                    tenantId, job.submissionId(), "PENDING", "AUDIO_FORMAT_UNSUPPORTED", now);
            return;
        }
        if (job.transcriptionAttempts() >= MAX_SUBMISSION_ATTEMPTS) {
            repository.markTranscriptionFailed(
                    tenantId, job.submissionId(), "PENDING", "ASR_RETRY_LIMIT_REACHED", now);
            return;
        }
        if (repository.claimPendingTranscription(tenantId, job.submissionId(), now) != 1) return;
        try {
            URI audioUrl = URI.create(cosStorage.generatePresignedGetUrl(
                    tenantId.toString(), job.audio().objectKey()).toExternalForm());
            var task = aiClient.createTranscriptionTask(audioUrl);
            repository.markTranscriptionProcessing(
                    tenantId, job.submissionId(), task.taskId(), task.requestId(), clock.instant());
            log.info("临时打卡录音转写已提交 submissionId={}", job.submissionId());
        } catch (AiClientException exception) {
            repository.markTranscriptionFailed(tenantId, job.submissionId(), "SUBMITTING",
                    safeCode(exception.code()), clock.instant());
        } catch (RuntimeException exception) {
            repository.markTranscriptionFailed(tenantId, job.submissionId(), "SUBMITTING",
                    "ASR_SUBMISSION_FAILED", clock.instant());
        }
    }

    private void pollTasks(Instant now) {
        for (TranscriptionJob job : repository.findProcessingTranscriptions(
                tenantId, now.minus(POLL_AFTER), POLL_BATCH_SIZE)) {
            try {
                var result = aiClient.describeTranscriptionTask(job.asrTaskId());
                if (result.state() == AsrState.WAITING || result.state() == AsrState.PROCESSING) {
                    repository.touchTranscription(tenantId, job.submissionId(), clock.instant());
                } else if (result.state() == AsrState.FAILED) {
                    repository.markTranscriptionFailed(tenantId, job.submissionId(), "PROCESSING",
                            safeCode(result.errorCode()), clock.instant());
                } else {
                    String transcript = normalizedTranscript(result.transcript());
                    if (transcript == null) {
                        repository.markTranscriptionFailed(tenantId, job.submissionId(), "PROCESSING",
                                "ASR_TRANSCRIPT_EMPTY", clock.instant());
                    } else {
                        repository.markTranscriptionSucceeded(
                                tenantId, job.submissionId(), transcript, clock.instant());
                        log.info("临时打卡录音转写已完成 submissionId={}", job.submissionId());
                    }
                }
            } catch (AiClientException exception) {
                if (isTransient(exception.code())) {
                    repository.touchTranscription(tenantId, job.submissionId(), clock.instant());
                } else {
                    repository.markTranscriptionFailed(tenantId, job.submissionId(), "PROCESSING",
                            safeCode(exception.code()), clock.instant());
                }
            } catch (RuntimeException exception) {
                repository.touchTranscription(tenantId, job.submissionId(), clock.instant());
            }
        }
    }

    private void summarizeOne(Instant now) {
        TranscriptionJob job = repository.findPendingSummary(tenantId).orElse(null);
        if (job == null || repository.claimPendingSummary(tenantId, job.submissionId(), now) != 1) return;
        try {
            var result = aiClient.summarize(job.transcript());
            repository.markSummarySucceeded(tenantId, job.submissionId(), result.summary(),
                    limit(result.model(), 128), clock.instant());
            log.info("临时打卡录音摘要已完成 submissionId={}", job.submissionId());
        } catch (AiClientException exception) {
            repository.markSummaryFailed(
                    tenantId, job.submissionId(), safeCode(exception.code()), clock.instant());
        } catch (RuntimeException exception) {
            repository.markSummaryFailed(
                    tenantId, job.submissionId(), "SUMMARY_GENERATION_FAILED", clock.instant());
        }
    }

    private static boolean isSupported(String contentType) {
        if (!StringUtils.hasText(contentType)) return false;
        String normalized = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_CONTENT_TYPES.contains(normalized);
    }

    private static String normalizedTranscript(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.length() > MAX_TRANSCRIPT_CHARACTERS) {
            return normalized.substring(0, MAX_TRANSCRIPT_CHARACTERS) + "\n[转写过长，已截断]";
        }
        return normalized;
    }

    private static boolean isTransient(String code) {
        String normalized = safeCode(code);
        return normalized.contains("CONNECTION")
                || normalized.contains("HTTP_429")
                || normalized.matches(".*HTTP_5[0-9][0-9].*");
    }

    private static String safeCode(String value) {
        if (!StringUtils.hasText(value)) return "UNKNOWN_FAILURE";
        String normalized = value.replaceAll("[^A-Za-z0-9_.-]", "_").toUpperCase(Locale.ROOT);
        return limit(normalized, 128);
    }

    private static String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }
}
