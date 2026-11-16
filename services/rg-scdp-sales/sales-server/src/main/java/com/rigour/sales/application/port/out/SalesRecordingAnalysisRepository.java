package com.rigour.sales.application.port.out;

import com.rigour.sales.application.port.out.SalesAudioAiClient.TranscriptSegment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 每段录音只有一个分析任务；保存结果不修改原音频及拜访有效性。 */
public interface SalesRecordingAnalysisRepository {
    void enqueue(UUID tenantId, UUID visitId, UUID clipId, Instant now);
    Optional<Job> claim(Instant now, UUID leaseId, Instant leaseUntil);
    void processing(Job job, String taskId, String requestId, Instant now);
    void transcribed(Job job, String transcript, List<TranscriptSegment> segments, String requestId, Instant now);
    void summarized(Job job, String summary, String model, String requestId, Instant now);
    void deferred(Job job, String errorCode, boolean terminal, Instant now);
    void waiting(Job job, Instant now);
    List<Analysis> list(UUID tenantId, UUID visitId);
    int retry(UUID tenantId, UUID visitId, UUID clipId, Instant now);

    record Job(UUID tenantId, UUID visitId, UUID clipId, UUID leaseId, String objectKey,
               String status, String taskId, String transcript, int attempts, Instant createdAt) {}
    record Analysis(UUID clipId, int clipIndex, String status, String transcript,
                    List<TranscriptSegment> segments, String summaryStatus, String summary,
                    String summaryModel, String errorCode, Instant updatedAt) {}
}
