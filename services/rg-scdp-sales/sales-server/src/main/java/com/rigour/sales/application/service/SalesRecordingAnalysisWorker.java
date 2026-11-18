package com.rigour.sales.application.service;

import com.rigour.sales.application.port.out.SalesAudioAiClient.AiClientException;
import com.rigour.sales.application.port.out.SalesRecordingAnalysisRepository;
import com.rigour.sales.application.port.out.SalesSpeechTranscriber;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recognition runs outside the lease transaction; completion does not approve a visit. */
@Component
@ConditionalOnProperty(name="rigour.sales.fieldwork.asr-enabled",havingValue="true")
public class SalesRecordingAnalysisWorker {
    private final SalesRecordingAnalysisRepository jobs;
    private final SalesSpeechTranscriber speech;
    private final Clock clock;

    public SalesRecordingAnalysisWorker(SalesRecordingAnalysisRepository jobs, SalesSpeechTranscriber speech, Clock clock) {
        this.jobs=jobs;this.speech=speech;this.clock=clock;
    }
    @Scheduled(fixedDelayString="${rigour.sales.fieldwork.asr-delay-ms:5000}",
            initialDelayString="${rigour.sales.fieldwork.asr-delay-ms:5000}",scheduler="salesRecordingAnalysisScheduler")
    public void process() {
        var now=clock.instant();
        var candidate=jobs.claim(now,UUID.randomUUID(),now.plus(Duration.ofMinutes(8)));
        if(candidate.isEmpty())return;
        var job=candidate.get();
        try {
            var result=speech.transcribe(job.tenantId(),job.objectKey(),job.speakers());
            jobs.transcribed(job,result,clock.instant());
        } catch(AiClientException error) {
            String code=error.code()!=null&&error.code().matches("[A-Z0-9_]{1,64}")?error.code():"ASR_REQUEST_FAILED";
            jobs.deferred(job,code,code.equals("ASR_HTTP_422")||code.equals("ASR_AUDIO_SIZE_LIMIT"),clock.instant());
        } catch(RuntimeException error) {
            jobs.deferred(job,"ASR_TEMPORARILY_UNAVAILABLE",false,clock.instant());
        }
    }
}
