package com.rigour.sales.infrastructure.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rigour.shared.file.FileStorage;
import com.rigour.sales.application.port.out.SalesAudioAiClient.AiClientException;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

class HttpFunasrSpeechTranscriberTest {
    private HttpServer server;
    private HttpFunasrSpeechTranscriber client;
    private final UUID tenant=UUID.randomUUID();
    private final FileStorage files=mock(FileStorage.class);
    private int status=200;
    private String body;
    private String authorization;
    private String contentType;
    private long contentLength;
    private byte[] payload;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var context=server.createContext("/v1/transcriptions",exchange->{
            authorization=exchange.getRequestHeaders().getFirst("Authorization");
            contentType=exchange.getRequestHeaders().getFirst("Content-Type");
            contentLength=Long.parseLong(exchange.getRequestHeaders().getFirst("Content-Length"));
            payload=exchange.getRequestBody().readAllBytes();
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.createContext("/v1/voice-profile",context.getHandler());server.start();
        client=new HttpFunasrSpeechTranscriber(files,RestClient.builder(),"http://127.0.0.1:"+server.getAddress().getPort(),"test-".repeat(8));
        when(files.open(tenant.toString(),"tenant/clip")).thenReturn(new ByteArrayInputStream("audio-bytes".getBytes(StandardCharsets.UTF_8)));
        body="""
                {"transcript":"测试转写","segments":[{"startMs":200,"endMs":900,"text":"测试转写","speakerId":null}],
                "model":"paraformer-large-zh-int8","requestId":"test-request","durationMs":1000,"processingMs":80,"peakRssMb":500.1,"speakerModel":"speaker-test","speakerProfiles":[]}
                """;
    }
    @AfterEach void close(){server.stop(0);}
    @Test void postsPrivateBytesWithAuthenticationAndReadsActualServerShape(){
        var result=client.transcribe(tenant,"tenant/clip",java.util.List.of());
        assertThat(result.transcript()).isEqualTo("测试转写");assertThat(result.segments()).hasSize(1);
        assertThat(result.model()).isEqualTo("paraformer-large-zh-int8");
        assertThat(authorization).isEqualTo("Bearer "+"test-".repeat(8));
        assertThat(contentType).startsWith("multipart/form-data;");
        assertThat(contentLength).isEqualTo(payload.length);
        assertThat(new String(payload,StandardCharsets.UTF_8)).contains("name=\"file\"", "audio-bytes", "speaker_context");
        verify(files).open(tenant.toString(),"tenant/clip");
    }
    @Test void keepsNoSpeechAsSuccessfulEmptyTranscript(){
        body="{\"transcript\":\"\",\"segments\":[],\"model\":\"paraformer-int8\",\"requestId\":\"empty\",\"durationMs\":1000,\"speakerModel\":\"speaker-test\",\"speakerProfiles\":[]}";
        assertThat(client.transcribe(tenant,"tenant/clip",java.util.List.of()).transcript()).isEmpty();
    }
    @Test void rejectsOffsetsOutsideOriginalAudio(){
        body=body.replace("900","1100");
        assertThatThrownBy(()->client.transcribe(tenant,"tenant/clip",java.util.List.of())).isInstanceOfSatisfying(AiClientException.class,e->assertThat(e.code()).isEqualTo("ASR_RESPONSE_INVALID"));
    }
    @Test void carriesVisitSpeakerContextAndRejectsUnknownSpeakerIds() {
        var profile=new com.rigour.sales.application.port.out.SalesSpeechTranscriber.SpeakerProfile(0,
                java.util.stream.IntStream.range(0,192).mapToObj(i->i==0?1.0:0.0).toList());
        var profiles=java.util.List.of(profile);
        body=body.replace("\"speakerId\":null","\"speakerId\":0").replace("\"speakerProfiles\":[]",
                "\"speakerProfiles\":"+new tools.jackson.databind.ObjectMapper().writeValueAsString(profiles));
        var result=client.transcribe(tenant,"tenant/clip",profiles);
        assertThat(result.speakerProfiles()).isEqualTo(profiles);
        assertThat(result.segments().getFirst().speakerId()).isZero();
        assertThat(new String(payload,StandardCharsets.UTF_8)).contains("speaker_context","embedding");
        body=body.replace("\"speakerId\":0","\"speakerId\":1");
        when(files.open(tenant.toString(),"tenant/clip")).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        assertThatThrownBy(()->client.transcribe(tenant,"tenant/clip",profiles)).isInstanceOf(AiClientException.class);
    }

    @Test void voiceEnrollmentPostsOnlySampleAndReadsValidatedFeatures(){
        var vector=java.util.stream.IntStream.range(0,192).mapToObj(i->i==0?1.0:0.0).toList();
        body=new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("model","pyannote3-campplus-zh-v1","embedding",vector,"speechMs",12000));
        var result=client.enroll(new byte[]{1,2,3});
        assertThat(result.embedding()).isEqualTo(vector);assertThat(result.speechMs()).isEqualTo(12000);
        assertThat(authorization).isEqualTo("Bearer "+"test-".repeat(8));
        status=422;body="private quality detail";
        assertThatThrownBy(()->client.enroll(new byte[]{1})).isInstanceOfSatisfying(AiClientException.class,e->assertThat(e.code()).isEqualTo("VOICE_QUALITY")).hasMessageNotContaining("private");
    }

    @Test void sanitizesUpstreamFailureWithoutExposingResponse(){
        status=429;body="private provider response";
        assertThatThrownBy(()->client.transcribe(tenant,"tenant/clip",java.util.List.of())).isInstanceOfSatisfying(AiClientException.class,e->assertThat(e.code()).isEqualTo("ASR_BUSY")).hasMessageNotContaining("private");
    }
}
