package com.rigour.sales.application.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.rigour.sales.application.port.out.*;
import com.rigour.sales.application.port.out.SalesAudioAiClient.*;
import com.rigour.sales.application.port.out.SalesRecordingAnalysisRepository.Job;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SalesRecordingAnalysisWorkerTest {
    private final Instant now=Instant.parse("2026-11-12T08:00:00Z");
    private final SalesRecordingAnalysisRepository repository=mock(SalesRecordingAnalysisRepository.class);
    private final SalesAudioAiClient ai=mock(SalesAudioAiClient.class);
    private final SalesAudioSource source=mock(SalesAudioSource.class);
    private final SalesRecordingAnalysisWorker worker=new SalesRecordingAnalysisWorker(repository,ai,source,Clock.fixed(now,ZoneOffset.UTC));
    private Job next(String status,String text){
        var job=new Job(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"private/audio",status,"task",text,0,now.minusSeconds(60));
        when(repository.claim(any(),any(),any())).thenReturn(Optional.of(job),Optional.empty());return job;
    }
    @Test void originalTranscriptAndActualTimeOffsetsAreSavedBeforeAnySummary(){
        var job=next("PROCESSING",null);
        var lines=List.of(new TranscriptSegment(2400,5100,"客户询问台呢规格",null));
        when(ai.describeTranscriptionTask("task")).thenReturn(new AsrTaskStatus("task",AsrState.SUCCEEDED,"客户询问台呢规格",null,"request",null,lines));
        worker.process();verify(repository).transcribed(job,"客户询问台呢规格",lines,"request",now);verify(ai,never()).summarize(any());
    }
    @Test void summaryFailureRetainsTheSuccessfulAsrJobAndRetriesOnlyTheSummary(){
        var job=next("SUCCEEDED","已保存原文");when(ai.summarize(job.transcript())).thenThrow(new AiClientException("SUMMARY_TIMEOUT"));
        worker.process();verify(repository).deferred(job,"SUMMARY_TIMEOUT",false,now);verify(ai,never()).createDetailedTranscriptionTask(any());verify(ai,never()).describeTranscriptionTask(any());
    }
    @Test void transientPollingFailureDoesNotCreateASecondProviderTask(){
        var job=next("PROCESSING",null);when(ai.describeTranscriptionTask("task")).thenThrow(new AiClientException("ASR_TIMEOUT"));
        worker.process();verify(repository).deferred(job,"ASR_TIMEOUT",false,now);verify(ai,never()).createDetailedTranscriptionTask(any());verifyNoInteractions(source);
    }
    @Test void providerFailureIsTerminalUntilExplicitRetry(){
        var job=next("PROCESSING",null);when(ai.describeTranscriptionTask("task")).thenReturn(new AsrTaskStatus("task",AsrState.FAILED,null,"NO_AUDIO","request",null));
        worker.process();verify(repository).deferred(job,"ASR_TASK_FAILED",true,now);verify(ai,never()).summarize(any());
    }
}
