package com.rigour.sales.application.service;

import com.rigour.sales.application.port.out.SalesAudioAiClient;
import com.rigour.sales.application.port.out.SalesAudioAiClient.AsrState;
import com.rigour.sales.application.port.out.SalesAudioSource;
import com.rigour.sales.application.port.out.SalesRecordingAnalysisRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 网络调用在租约事务之外；原音频及 CRM 有效性不受分析结果写入影响。 */
@Component
@ConditionalOnProperty(name="rigour.sales.fieldwork.ai-enabled", havingValue="true")
public class SalesRecordingAnalysisWorker {
    private final SalesRecordingAnalysisRepository jobs;
    private final SalesAudioAiClient ai;
    private final SalesAudioSource source;
    private final Clock clock;

    public SalesRecordingAnalysisWorker(SalesRecordingAnalysisRepository jobs, SalesAudioAiClient ai,
                                       SalesAudioSource source, Clock clock) {
        this.jobs=jobs; this.ai=ai; this.source=source; this.clock=clock;
    }

    @Scheduled(fixedDelayString="${rigour.sales.fieldwork.ai-delay-ms:10000}",
            initialDelayString="${rigour.sales.fieldwork.ai-delay-ms:10000}", scheduler="salesRecordingAnalysisScheduler")
    public void process() {
        // Bound network work per tick. Each clip is independently leased across server instances.
        for (int index=0; index<3; index++) {
            var now=clock.instant();
            var candidate=jobs.claim(now,UUID.randomUUID(),now.plus(Duration.ofMinutes(3)));
            if (candidate.isEmpty()) return;
            var job=candidate.get();
            try {
                if ("PENDING".equals(job.status())) {
                    var task=ai.createDetailedTranscriptionTask(source.temporaryUrl(job.tenantId(),job.objectKey()));
                    jobs.processing(job,task.taskId(),task.requestId(),clock.instant());
                } else if ("PROCESSING".equals(job.status())) {
                    var result=ai.describeTranscriptionTask(job.taskId());
                    if (result.state()==AsrState.SUCCEEDED) {
                        String transcript=result.transcript()==null?"":result.transcript();
                        if (transcript.length()>2_000_000) throw new SalesAudioAiClient.AiClientException("ASR_RESULT_TOO_LARGE");
                        jobs.transcribed(job,transcript,result.segments(),result.requestId(),clock.instant());
                    } else if (result.state()==AsrState.FAILED) {
                        jobs.deferred(job,"ASR_TASK_FAILED",true,clock.instant());
                    } else if (Duration.between(job.createdAt(),now).toDays()>=7) {
                        jobs.deferred(job,"ASR_TIMEOUT",true,clock.instant());
                    } else jobs.waiting(job,clock.instant());
                } else {
                    var summary=ai.summarize(job.transcript());
                    jobs.summarized(job,summary.summary(),summary.model(),summary.requestId(),clock.instant());
                }
            } catch (SalesAudioAiClient.AiClientException error) {
                String code=error.code()!=null&&error.code().matches("[A-Z0-9_]{1,64}")?error.code():"AI_REQUEST_FAILED";
                jobs.deferred(job,code,false,clock.instant());
            } catch (RuntimeException error) {
                // Neither raw transcript nor presigned URL is included in diagnostics.
                jobs.deferred(job,"ANALYSIS_TEMPORARILY_UNAVAILABLE",false,clock.instant());
            }
        }
    }
}
