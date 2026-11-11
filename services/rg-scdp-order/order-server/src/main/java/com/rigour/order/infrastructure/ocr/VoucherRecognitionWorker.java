package com.rigour.order.infrastructure.ocr;

import com.rigour.order.application.port.out.FundAttachmentUrlResolver;
import com.rigour.order.infrastructure.persistence.repository.JdbcVoucherRecognitionStore;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** A bounded local subprocess; no external OCR API receives the voucher. */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix="rigour.order.voucher-recognition",name="enabled",havingValue="true")
public class VoucherRecognitionWorker {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcVoucherRecognitionStore store;
    private final FundAttachmentUrlResolver urls;
    private final String python;
    public VoucherRecognitionWorker(JdbcVoucherRecognitionStore store,ObjectProvider<FundAttachmentUrlResolver> urls,
            @Value("${rigour.order.voucher-recognition.python:python3}") String python) {
        this.store=store;this.urls=urls.getIfAvailable();this.python=python;
    }
    @Value("${rigour.order.voucher-recognition.discover-enabled:true}")
    private boolean discoverEnabled = true;
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void discover() { if(discoverEnabled) store.discoverUnrecognized(100); }

    @Scheduled(fixedDelayString="${rigour.order.voucher-recognition.poll-ms:5000}")
    public void runOne() {
        var job=store.claim(); if(job==null)return;
        Path directory=null; Process process=null;
        try {
            String cached=null;
            if(job.get("result_json")!=null) {
                cached=job.get("result_json").toString();
                var previous=JSON.readTree(cached);
                if(JdbcVoucherRecognitionStore.SCRIPT_FINGERPRINT.equals(previous.path("ruleScriptSha256").asText())) {
                    store.complete(job,previous,cached);return;
                }
                if(!previous.path("lines").isArray() || previous.path("lines").isEmpty()) cached=null;
            }
            directory=Files.createTempDirectory("rigour-voucher-ocr-");
            Path input=directory.resolve("voucher"),output=directory.resolve("result.json"),script=directory.resolve("recognize.py");
            if(cached!=null) Files.writeString(input,cached);
            else {
                if(urls==null)throw new IllegalStateException("COS_NOT_CONFIGURED");
                String url=urls.temporaryUrl((String)job.get("tenant_id"),(String)job.get("voucher_key"));
                if(url==null)throw new IllegalStateException("COS_NOT_CONFIGURED");
                URI uri=URI.create(url);
                if(!"https".equals(uri.getScheme())||!uri.getHost().endsWith(".myqcloud.com"))throw new IllegalStateException("INVALID_COS_URL");
                try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()) {
                    var response=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
                    try(var body=response.body()) {
                        if(response.statusCode()!=200)throw new IllegalStateException("COS_READ_FAILED");
                        byte[] bytes=body.readNBytes(12*1024*1024+1);
                        if(bytes.length>12*1024*1024)throw new IllegalStateException("IMAGE_TOO_LARGE");
                        Files.write(input,bytes);
                    }
                }
            }
            try(var resource=getClass().getResourceAsStream("/ocr/recognize_voucher.py")) { if(resource==null)throw new IllegalStateException("OCR_SCRIPT_MISSING");Files.copy(resource,script); }
            var arguments=cached==null ? java.util.List.of(python,script.toString(),input.toString(),output.toString())
                    : java.util.List.of(python,script.toString(),"--reparse",input.toString(),output.toString());
            process=new ProcessBuilder(arguments)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(120,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("OCR_TIMEOUT");}
            if(process.exitValue()!=0||!Files.exists(output))throw new IllegalStateException("OCR_PROCESS_FAILED");
            var result=(tools.jackson.databind.node.ObjectNode)JSON.readTree(Files.readString(output));
            result.put("ruleScriptSha256",JdbcVoucherRecognitionStore.SCRIPT_FINGERPRINT);
            store.complete(job,result,JSON.writeValueAsString(result));
        } catch(Exception error) {
            if(error instanceof InterruptedException)Thread.currentThread().interrupt();
            // Never include signed URLs, image text, or subprocess output in logs/errors.
            store.fail(job,error instanceof IllegalStateException?error.getMessage():"OCR_UNAVAILABLE");
        } finally {
            if(process!=null&&process.isAlive())process.destroyForcibly();
            if(directory!=null)try(var files=Files.list(directory)){for(Path f:files.toList())Files.deleteIfExists(f);Files.deleteIfExists(directory);}catch(java.io.IOException ignored){}
        }
    }
}
