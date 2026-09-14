package com.rigour.sales.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.rigour.sales.application.port.out.SalesWorkRecordingRepository.RecordingClipRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JdbcSalesWorkRecordingRepositoryTest {

    private static final Instant START = Instant.parse("2026-08-17T01:00:00Z");

    @Test
    void duplicateHashWithChangedClientIdCountsOnlyOnceAndRequiresReview() {
        var summary = JdbcSalesWorkRecordingRepository.summarize(List.of(
                verifiedClip("client-1", 0, "same-hash", START, 30_000),
                verifiedClip("client-2", 1, "same-hash", START.plusSeconds(40), 30_000)),
                30, false);

        assertThat(summary.verifiedTotalDurationMs()).isEqualTo(30_000L);
        assertThat(summary.evidenceStatus()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void sameDecodedContentWithDifferentFileHashesCountsOnlyOnce() {
        var summary = JdbcSalesWorkRecordingRepository.summarize(List.of(
                verifiedClip("client-1", 0, "file-hash-1", "pcm-same", START, 30_000),
                verifiedClip("client-2", 1, "file-hash-2", "pcm-same",
                        START.plusSeconds(40), 30_000)), 30, false);

        assertThat(summary.verifiedTotalDurationMs()).isEqualTo(30_000L);
        assertThat(summary.evidenceStatus()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void differentAudioWithOverlappingIntervalsUsesUnionAndRequiresReview() {
        var summary = JdbcSalesWorkRecordingRepository.summarize(List.of(
                verifiedClip("client-1", 0, "hash-1", START, 40_000),
                verifiedClip("client-2", 1, "hash-2", START.plusSeconds(20), 40_000)),
                30, false);

        assertThat(summary.verifiedTotalDurationMs()).isEqualTo(60_000L);
        assertThat(summary.maximumObservedGapMs()).isZero();
        assertThat(summary.evidenceStatus()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void decodedIntervalGapOverPolicyRequiresReview() {
        List<RecordingClipRow> clips = List.of(
                verifiedClip("client-1", 0, "hash-1", START, 30_000, 5_000),
                verifiedClip("client-2", 1, "hash-2", START.plusSeconds(65), 30_000));

        var rejected = JdbcSalesWorkRecordingRepository.summarize(clips, 30, false);
        var accepted = JdbcSalesWorkRecordingRepository.summarize(clips, 40, false);

        assertThat(rejected.verifiedTotalDurationMs()).isEqualTo(60_000L);
        // 客户端 recordedTo 把申报 gap 伪装成 30 秒；可信解码区间的真实 gap 是 35 秒。
        assertThat(rejected.maximumObservedGapMs()).isEqualTo(35_000L);
        assertThat(rejected.evidenceStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(accepted.evidenceStatus()).isEqualTo("TECHNICALLY_VERIFIED");
    }

    @Test
    void subSecondMobileSegmentationOverlapUsesUnionWithoutAutomaticReview() {
        var summary = JdbcSalesWorkRecordingRepository.summarize(List.of(
                verifiedClip("client-1", 0, "hash-1", START, 30_000),
                verifiedClip("client-2", 1, "hash-2", START.plusMillis(29_100), 30_000)),
                30, false);

        assertThat(summary.verifiedTotalDurationMs()).isEqualTo(59_100L);
        assertThat(summary.maximumObservedGapMs()).isZero();
        assertThat(summary.evidenceStatus()).isEqualTo("TECHNICALLY_VERIFIED");
    }

    private static RecordingClipRow verifiedClip(String clientClipId, int clipIndex, String sha256,
                                                   Instant recordedFrom, long verifiedDurationMs) {
        return verifiedClip(clientClipId, clipIndex, sha256, "pcm-" + sha256,
                recordedFrom, verifiedDurationMs, 0L);
    }

    private static RecordingClipRow verifiedClip(String clientClipId, int clipIndex, String sha256,
                                                   String decodedContentHash, Instant recordedFrom,
                                                   long verifiedDurationMs) {
        return verifiedClip(clientClipId, clipIndex, sha256, decodedContentHash,
                recordedFrom, verifiedDurationMs, 0L);
    }

    private static RecordingClipRow verifiedClip(String clientClipId, int clipIndex, String sha256,
                                                   Instant recordedFrom, long verifiedDurationMs,
                                                   long claimedDurationExtensionMs) {
        return verifiedClip(clientClipId, clipIndex, sha256, "pcm-" + sha256,
                recordedFrom, verifiedDurationMs, claimedDurationExtensionMs);
    }

    private static RecordingClipRow verifiedClip(String clientClipId, int clipIndex, String sha256,
                                                   String decodedContentHash, Instant recordedFrom,
                                                   long verifiedDurationMs,
                                                   long claimedDurationExtensionMs) {
        Instant recordedTo = recordedFrom.plusMillis(verifiedDurationMs + claimedDurationExtensionMs);
        return new RecordingClipRow(UUID.randomUUID(), UUID.randomUUID(), clientClipId, clipIndex,
                "tenant/" + clientClipId + ".aac", "audio/aac", 1_024L, sha256,
                decodedContentHash, verifiedDurationMs, verifiedDurationMs, "RECEIVED", "VERIFIED",
                recordedFrom, recordedTo, recordedTo.plusSeconds(1));
    }
}
