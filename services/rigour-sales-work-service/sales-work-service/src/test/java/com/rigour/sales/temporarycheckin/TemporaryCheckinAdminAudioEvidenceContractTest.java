package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 固化后台录音时间证据的展示与媒体时长解析契约。 */
class TemporaryCheckinAdminAudioEvidenceContractTest {

    @Test
    void labelsClientTimingAsReportedAndNeverTreatsUploadTimeAsRecordingTime() throws IOException {
        String script = resource("static/sales-checkin/admin/admin.js");
        String styles = resource("static/sales-checkin/admin/admin.css");

        assertThat(script)
                .contains("captureSource: normalizeAudioCaptureSource(segment.captureSource)")
                .contains("clientStartedAt: segment.clientStartedAt || null")
                .contains("fileLastModifiedAt: segment.fileLastModifiedAt || null")
                .contains("服务端上传时间")
                .contains("页面录制开始（客户端报告）")
                .contains("页面录制结束（客户端报告）")
                .contains("客户端声明为页面录制（不可独立证明）")
                .contains("客户端报告时间顺序一致（仅供参考）")
                .contains("文件修改时间（客户端报告，不可核验）")
                .contains("录制时间未采集")
                .contains("不能证明实际录制时间")
                .doesNotContain("录制时间 ${formatFullDateTime(segment.uploadedAt)}");
        assertThat(styles)
                .contains(".audio-timing-evidence")
                .contains("border: 1px solid #d9aaa4;")
                .contains(".audio-timing-evidence__status.is-danger");
    }

    @Test
    void parsesActualMediaDurationFromLoadedMetadata() throws IOException {
        String script = resource("static/sales-checkin/admin/admin.js");

        assertThat(script)
                .contains("audio.preload = \"metadata\"")
                .contains("audio.addEventListener(\"loadedmetadata\"")
                .contains("audio.duration * 1000")
                .contains("媒体解析时长")
                .contains("客户端报告时长")
                .contains("mediaDuration.dataset.audioMediaDuration = \"true\"");
    }

    private static String resource(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
