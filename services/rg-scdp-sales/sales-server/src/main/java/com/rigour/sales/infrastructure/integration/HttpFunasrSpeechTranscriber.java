package com.rigour.sales.infrastructure.integration;

import com.rigour.sales.application.port.out.SalesSpeechTranscriber;
import com.rigour.sales.application.port.out.SalesAudioAiClient.AiClientException;
import com.rigour.sales.application.port.out.SalesAudioAiClient.TranscriptSegment;
import com.rigour.shared.file.FileStorage;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name="rigour.sales.fieldwork.asr-enabled",havingValue="true")
public class HttpFunasrSpeechTranscriber implements SalesSpeechTranscriber, com.rigour.sales.application.port.out.SalesVoiceEnrollmentClient {
    private final FileStorage files;
    private final RestClient client;
    private final String endpoint;
    private final String token;
    private final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();

    public HttpFunasrSpeechTranscriber(FileStorage files, RestClient.Builder builder,
            @Value("${rigour.sales.fieldwork.asr-base-url}") String base,
            @Value("${rigour.sales.fieldwork.asr-token}") String token) {
        if(token==null || token.length()<32) throw new IllegalStateException("ASR access token is missing");
        this.files=files;this.token=token;this.endpoint=base.replaceAll("/+$","")+"/v1/transcriptions";
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));factory.setReadTimeout(Duration.ofSeconds(420));
        this.client=builder.requestFactory(factory).requestInterceptor((request,body,execution)->{
            request.getHeaders().setContentLength(body.length);return execution.execute(request,body);
        }).build();
    }

    @Override public Enrollment enroll(byte[] audio) {
        var parts=new LinkedMultiValueMap<String,Object>();
        parts.add("file",new ByteArrayResource(audio){@Override public String getFilename(){return "voice.audio";}});
        try {
            var voice=client.post().uri(endpoint.replace("/transcriptions","/voice-profile")).headers(h->h.setBearerAuth(token))
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve().body(Enrollment.class);
            if(voice==null||!com.rigour.sales.domain.VoiceIdentity.valid(voice.embedding())||voice.model()==null||voice.model().length()>128)
                throw new AiClientException("VOICE_RESPONSE_INVALID");
            return voice;
        } catch(org.springframework.web.client.RestClientResponseException e) {
            throw new AiClientException(e.getStatusCode().value()==422?"VOICE_QUALITY":"VOICE_UNAVAILABLE");
        } catch(AiClientException e){throw e;} catch(RuntimeException e){throw new AiClientException("VOICE_UNAVAILABLE");}
    }

    @Override public Result transcribe(UUID tenant, String key, List<SpeakerProfile> speakers) {
        byte[] bytes;
        try(var input=files.open(tenant.toString(),key)) {
            bytes=input.readNBytes(26_214_401);
        } catch(Exception error) { throw new AiClientException("ASR_AUDIO_READ_FAILED"); }
        if(bytes.length==0 || bytes.length>26_214_400) throw new AiClientException("ASR_AUDIO_SIZE_LIMIT");
        var parts=new LinkedMultiValueMap<String,Object>();
        parts.add("file",new ByteArrayResource(bytes){@Override public String getFilename(){return "clip.audio";}});
        parts.add("speaker_context",json.writeValueAsString(speakers));
        try {
            var result=client.post().uri(endpoint).headers(h->h.setBearerAuth(token))
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve().body(Response.class);
            if(result==null || result.transcript()==null || result.transcript().length()>100_000
                    || result.segments()==null || result.durationMs()<=0 || result.durationMs()>600_999
                    || result.model()==null || result.model().length()>128 || result.requestId()==null
                    || result.requestId().length()>128 || result.speakerModel()==null || result.speakerModel().length()>128
                    || result.speakerProfiles()==null || result.speakerProfiles().size()>64
                    || result.speakerProfiles().size()<speakers.size()) throw new AiClientException("ASR_RESPONSE_INVALID");
            for(int i=0;i<result.speakerProfiles().size();i++) {
                var profile=result.speakerProfiles().get(i);
                if(profile.id()!=i || profile.embedding()==null || profile.embedding().size()!=192
                        || profile.embedding().stream().anyMatch(v->v==null || !Double.isFinite(v)))
                    throw new AiClientException("ASR_RESPONSE_INVALID");
                double norm=profile.embedding().stream().mapToDouble(v->v*v).sum();
                if(Math.abs(norm-1)>0.05)throw new AiClientException("ASR_RESPONSE_INVALID");
            }
            long previous=0;
            for(var segment:result.segments()) {
                if(segment.startMs()<previous || segment.endMs()<=segment.startMs()
                        || segment.endMs()>result.durationMs() || (segment.speakerId()!=null && (segment.speakerId()<0 || segment.speakerId()>=result.speakerProfiles().size())) || segment.text()==null || segment.text().length()>20_000)
                    throw new AiClientException("ASR_RESPONSE_INVALID");
                previous=segment.endMs();
            }
            return new Result(result.transcript(),List.copyOf(result.segments()),result.model(),result.requestId(),result.speakerModel(),List.copyOf(result.speakerProfiles()));
        } catch(AiClientException error) { throw error; }
        catch(org.springframework.web.client.RestClientResponseException error) {
            throw new AiClientException(error.getStatusCode().value()==429?"ASR_BUSY":"ASR_HTTP_"+error.getStatusCode().value());
        } catch(RuntimeException error) { throw new AiClientException("ASR_UNAVAILABLE"); }
    }
    public record Response(String transcript,List<TranscriptSegment> segments,String model,String requestId,long durationMs,String speakerModel,List<SpeakerProfile> speakerProfiles) {}
}
