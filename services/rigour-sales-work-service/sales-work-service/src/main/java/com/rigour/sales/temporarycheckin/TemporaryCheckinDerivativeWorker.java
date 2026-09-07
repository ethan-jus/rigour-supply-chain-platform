package com.rigour.sales.temporarycheckin;

import com.rigour.shared.file.FileMetadata;
import com.rigour.shared.file.FileStorage;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 限并发派生器：原件始终保留，解码在独立进程内限时执行，失败不影响拜访提交。 */
@Component
@ConditionalOnProperty(prefix="rigour.sales.temporary-checkin",name="enabled",havingValue="true")
class TemporaryCheckinDerivativeWorker {
    private final TemporaryCheckinDerivativeRepository repository;
    private final TemporaryCheckinAdminThumbnailer thumbnailer;
    private final FileStorage storage;
    private final Clock clock;
    private final UUID tenant;
    private final long maximumSourceBytes;
    private final String ffmpeg;
    private final String ffprobe;

    TemporaryCheckinDerivativeWorker(TemporaryCheckinDerivativeRepository repository,
            TemporaryCheckinAdminThumbnailer thumbnailer,FileStorage storage,
            TemporaryCheckinProperties properties,Clock clock,
            @Value("${rigour.sales.temporary-checkin.media.ffmpeg:ffmpeg}") String ffmpeg,
            @Value("${rigour.sales.temporary-checkin.media.ffprobe:ffprobe}") String ffprobe) {
        this.repository=repository;this.thumbnailer=thumbnailer;this.storage=storage;this.clock=clock;
        this.tenant=properties.requireTenantId();this.maximumSourceBytes=properties.getMaxAudioBytes();
        this.ffmpeg=ffmpeg;this.ffprobe=ffprobe;
    }

    @Scheduled(initialDelayString="${rigour.sales.temporary-checkin.media.initial-delay:60s}",
            fixedDelayString="${rigour.sales.temporary-checkin.media.poll-interval:3s}")
    public synchronized void process() {
        for (var stale:repository.obsolete(tenant)) {
            try {
                if (stale.derivedKey()!=null) storage.delete(tenant.toString(),stale.derivedKey());
                repository.remove(tenant,stale.id());
            } catch (RuntimeException ignored) { /* 保留任务让下一轮重试清理。 */ }
        }
        repository.discover(tenant,clock.instant());
        var jobs=repository.next(tenant,clock.instant());
        if (jobs.isEmpty()) return;
        var job=jobs.getFirst();
        UUID lease=UUID.randomUUID();
        if (!repository.claim(tenant,job.id(),lease,clock.instant())) return;
        Path directory=null;
        String uploadedKey=null;
        Long duration=null;
        try {
            if (job.sourceBytes() < 1 || job.sourceBytes()>maximumSourceBytes)
                throw new IllegalArgumentException("SOURCE_LIMIT");
            if ("IMAGE".equals(job.kind())) {
                var media=new TemporaryCheckinService.AdminMedia(
                        ()->storage.open(tenant.toString(),job.sourceKey()),job.sourceBytes(),job.sourceType(),job.filename());
                var thumbnail=thumbnailer.create(media);
                repository.success(tenant,job.id(),lease,null,thumbnail.bytes(),null,null,clock.instant());
                return;
            }
            directory=Files.createTempDirectory("checkin-media-");
            Path source=directory.resolve("source.bin");
            try (InputStream input=storage.open(tenant.toString(),job.sourceKey());
                    OutputStream output=Files.newOutputStream(source)) {
                byte[] buffer=new byte[8192];long total=0;int count;
                while ((count=input.read(buffer))>=0) {
                    total+=count;if (total>maximumSourceBytes) throw new IllegalArgumentException("SOURCE_LIMIT");
                    output.write(buffer,0,count);
                }
            }
            Path probeOutput=directory.resolve("duration.txt");
            run(List.of(ffprobe,"-v","error","-protocol_whitelist","file,pipe","-show_entries",
                    "format=duration","-of","default=noprint_wrappers=1:nokey=1",source.toString()),
                    probeOutput,directory.resolve("probe-error.txt"),15);
            String raw=Files.readString(probeOutput).trim();
            double seconds=Double.parseDouble(raw);
            if (!Double.isFinite(seconds) || seconds<=0) throw new IllegalArgumentException("DURATION_UNKNOWN");
            duration=Math.round(seconds*1000);
            // 最长三小时的兼容副本，避免超长容器消耗无限CPU/磁盘；超限保留原件与已解析时长。
            if (seconds>10800) throw new IllegalArgumentException("DURATION_LIMIT");
            Path converted=directory.resolve("playback.mp3");
            run(List.of(ffmpeg,"-nostdin","-hide_banner","-loglevel","error","-protocol_whitelist","file,pipe",
                    "-threads","1","-i",source.toString(),"-map","0:a:0","-vn","-ac","1","-ar","24000",
                    "-codec:a","libmp3lame","-b:a","48k","-threads","1","-fs","83886080","-y",converted.toString()),
                    directory.resolve("convert-output.txt"),directory.resolve("convert-error.txt"),60);
            long size=Files.size(converted);
            if (size<1 || size>80L*1024*1024) throw new IllegalArgumentException("OUTPUT_LIMIT");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream input=Files.newInputStream(converted)) {
                byte[] buffer=new byte[8192];int count;
                while((count=input.read(buffer))>=0) digest.update(buffer,0,count);
            }
            String sha=HexFormat.of().formatHex(digest.digest());
            uploadedKey=tenant+"/temporary-sales-checkin/"+job.submissionId()+"/derived/"+job.id()+"-"+lease+".mp3";
            try(InputStream input=Files.newInputStream(converted)) {
                storage.put(new FileMetadata(tenant.toString(),uploadedKey,"playback.mp3","audio/mpeg",size,sha,
                        OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC)),input);
            }
            if (repository.success(tenant,job.id(),lease,duration,null,uploadedKey,size,clock.instant()))
                uploadedKey=null;
        } catch (Exception error) {
            String code=error instanceof IllegalArgumentException ? error.getMessage() : "DECODE_OR_STORAGE_FAILED";
            if (code==null || !code.matches("[A-Z_]{1,64}")) code="DECODE_OR_STORAGE_FAILED";
            repository.failed(tenant,job.id(),lease,code,duration,clock.instant());
        } finally {
            if (uploadedKey!=null) try { storage.delete(tenant.toString(),uploadedKey); } catch(RuntimeException ignored) { }
            if (directory!=null) try (var paths=Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path->{
                    try { Files.deleteIfExists(path); } catch(java.io.IOException ignored) { }
                });
            } catch(java.io.IOException ignored) { }
        }
    }

    private static void run(List<String> command,Path output,Path error,int timeoutSeconds) throws Exception {
        Process process=new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(timeoutSeconds,TimeUnit.SECONDS)) throw new IllegalArgumentException("PROCESS_TIMEOUT");
            if (process.exitValue()!=0) throw new IllegalArgumentException("DECODE_UNSUPPORTED");
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5,TimeUnit.SECONDS);
            }
        }
    }
}
