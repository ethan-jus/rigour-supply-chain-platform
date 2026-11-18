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
    private final Instant now=Instant.parse("2026-11-17T08:00:00Z");
    private final SalesRecordingAnalysisRepository repository=mock(SalesRecordingAnalysisRepository.class);
    private final SalesSpeechTranscriber speech=mock(SalesSpeechTranscriber.class);
    private final SalesRecordingAnalysisWorker worker=new SalesRecordingAnalysisWorker(repository,speech,Clock.fixed(now,ZoneOffset.UTC));
    private Job next(){
        var job=new Job(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"private/audio",0,0,List.of());
        when(repository.claim(any(),any(),any())).thenReturn(Optional.of(job));return job;
    }
    @Test void savesOriginalTranscriptOffsetsAndModel(){
        var job=next();
        var lines=List.of(new TranscriptSegment(2400,5100,"客户询问台呢规格",null));
        when(speech.transcribe(job.tenantId(),job.objectKey(),job.speakers())).thenReturn(new SalesSpeechTranscriber.Result("客户询问台呢规格",lines,"paraformer-int8","request","speaker-test",List.of()));
        worker.process();verify(repository).transcribed(eq(job),argThat(result->result.transcript().equals("客户询问台呢规格") && result.segments().equals(lines)),eq(now));
        verify(repository,never()).deferred(any(),any(),anyBoolean(),any());
    }
    @Test void transientFailureIsRetriedWithoutLosingTheJob(){
        var job=next();when(speech.transcribe(any(),any(),any())).thenThrow(new AiClientException("ASR_BUSY"));
        worker.process();verify(repository).deferred(job,"ASR_BUSY",false,now);
    }
    @Test void unreadableAudioIsTerminalUntilExplicitRetry(){
        var job=next();when(speech.transcribe(any(),any(),any())).thenThrow(new AiClientException("ASR_HTTP_422"));
        worker.process();verify(repository).deferred(job,"ASR_HTTP_422",true,now);
    }
    @Test void unexpectedFailureDoesNotPersistSensitiveExceptionText(){
        var job=next();when(speech.transcribe(any(),any(),any())).thenThrow(new IllegalStateException("secret response"));
        worker.process();verify(repository).deferred(job,"ASR_TEMPORARILY_UNAVAILABLE",false,now);
    }
    @Test void idleWorkerDoesNotContactProvider(){
        when(repository.claim(any(),any(),any())).thenReturn(Optional.empty());
        worker.process();verifyNoInteractions(speech);
    }
}
