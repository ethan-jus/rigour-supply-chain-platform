package com.rigour.order.infrastructure.ocr;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import com.rigour.order.infrastructure.persistence.repository.JdbcVoucherRecognitionStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
class VoucherRecognitionWorkerTest {
    @TempDir Path directory;
    @Test void currentScriptResultIsReusedWithoutCosOrPython() {
        var store=mock(JdbcVoucherRecognitionStore.class);
        String cached="{\"ruleScriptSha256\":\""+JdbcVoucherRecognitionStore.SCRIPT_FINGERPRINT+"\",\"paidTime\":\"2026-11-10T14:19:33Z\"}";
        var job=Map.<String,Object>of("result_json",cached);
        when(store.claim()).thenReturn(job);
        new VoucherRecognitionWorker(store,mock(ObjectProvider.class),"/not-needed/python").runOne();
        verify(store).complete(eq(job),argThat(r->r.path("paidTime").asText().equals("2026-11-10T14:19:33Z")),eq(cached));
        verify(store,never()).fail(any(),any());
    }
    @Test void outdatedCachedTextIsReparsedWithoutCosAndTaggedWithPackagedScript() throws Exception {
        var store=mock(JdbcVoucherRecognitionStore.class);
        var job=Map.<String,Object>of("result_json","{\"engine\":\"rules-1\",\"lines\":[{\"text\":\"支付时间\"}]}");
        when(store.claim()).thenReturn(job);
        // Test the worker process contract without requiring the OCR model or network.
        Path python=directory.resolve("python");
        Files.writeString(python,"""
                #!/bin/sh
                [ "$2" = "--reparse" ] || exit 11
                [ -s "$3" ] || exit 12
                printf '%s' '{"paidTime":"2026-11-10T14:19:33Z","recognitionStatus":"REVIEW"}' > "$4"
                """);
        assertThat(python.toFile().setExecutable(true)).isTrue();
        new VoucherRecognitionWorker(store,mock(ObjectProvider.class),python.toString()).runOne();
        verify(store).complete(eq(job),argThat(r->r.path("ruleScriptSha256").asText().equals(JdbcVoucherRecognitionStore.SCRIPT_FINGERPRINT)),argThat(s->JsonMapper.builder().build().readTree(s).path("paidTime").asText().equals("2026-11-10T14:19:33Z")));
        verify(store,never()).fail(any(),any());
    }
    @Test void devDiscoveryOffDoesNotMaintainHistoricalData() {
        var store=mock(JdbcVoucherRecognitionStore.class);
        var worker=new VoucherRecognitionWorker(store,mock(ObjectProvider.class),"python3");
        ReflectionTestUtils.setField(worker,"discoverEnabled",false);
        worker.discover(); verifyNoInteractions(store);
        ReflectionTestUtils.setField(worker,"discoverEnabled",true);
        worker.discover(); verify(store).discoverUnrecognized(100);
    }
}
