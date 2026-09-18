package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rigour.sales.temporarycheckin.TemporaryCheckinException.AudioRejectionReason;
import com.rigour.sales.temporarycheckin.TemporaryCheckinExceptionHandler.AudioErrorResponse;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.ErrorResponse;
import com.rigour.shared.context.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** 录音拒绝的 HTTP 兼容、安全诊断和 multipart 前置错误回归。 */
class TemporaryCheckinAudioRejectionHandlerTest {

    private static final String SUBMISSION_ID = "10000000-0000-0000-0000-000000000001";
    private static final String SEGMENT_ID = "20000000-0000-0000-0000-000000000001";
    private static final String AUDIO_PATH = "/sales-checkin/api/v1/submissions/" + SUBMISSION_ID
            + "/media/audio/" + SEGMENT_ID;
    private final TemporaryCheckinExceptionHandler handler = new TemporaryCheckinExceptionHandler();
    private final Logger logger = (Logger) LoggerFactory.getLogger(TemporaryCheckinExceptionHandler.class);
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureDiagnosticLog() {
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        RequestContext.set("test-audio-request-123", "zh-CN");
    }

    @AfterEach
    void releaseContextAndLog() {
        RequestContext.clear();
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void preservesBusinessHttpAndCodeWhileReturningAnActionableReason() {
        for (AudioRejectionReason reason : AudioRejectionReason.values()) {
            var response = handler.handle(TemporaryCheckinException.audioRejected(reason), uploadRequest());
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            var body = (AudioErrorResponse) response.getBody();
            assertThat(body.code()).isEqualTo("TEMP_CHECKIN_BAD_REQUEST");
            assertThat(body.reason()).isEqualTo(reason.name());
            assertThat(body.requestId()).isEqualTo("test-audio-request-123");
            assertThat(body.message()).isNotBlank();
        }
    }

    @Test
    void logsOnlyValidatedIdentifiersAndReasonWithoutMediaOrCredentials() {
        var request = uploadRequest();
        request.addHeader("X-Submission-Key", "never-log-key");
        request.addHeader("Cookie", "never-log-cookie");
        request.setQueryString("file=never-log-filename&transcript=never-log-transcript");
        request.setContent("never-log-payload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        handler.handle(TemporaryCheckinException.badRequest("never-log-client-error-message"), request);

        assertThat(logs.list).hasSize(1);
        var event = logs.list.getFirst();
        assertThat(event.getFormattedMessage())
                .contains("requestId=test-audio-request-123", "submissionId=" + SUBMISSION_ID,
                        "segmentId=" + SEGMENT_ID, "status=400", "reason=BUSINESS_RULE")
                .doesNotContain("never-log", "Cookie", "X-Submission-Key");
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void preservesConflictAndDoesNotMislabelItAsContentRejection() {
        var response = handler.handle(TemporaryCheckinException.conflict("草稿媒体已变化，请刷新后重试"), uploadRequest());
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        var body = (AudioErrorResponse) response.getBody();
        assertThat(body.code()).isEqualTo("TEMP_CHECKIN_CONFLICT");
        assertThat(body.reason()).isEqualTo("BUSINESS_RULE");
        assertThat(body.message()).isEqualTo("草稿媒体已变化，请刷新后重试");
    }

    @Test
    void diagnosesServletLimitAndMissingFileWithoutChangingTheirHttpStatus() throws Exception {
        var tooLarge = handler.tooLarge(uploadRequest());
        assertThat(tooLarge.getStatusCode().value()).isEqualTo(413);
        assertThat(((AudioErrorResponse) tooLarge.getBody()).code()).isEqualTo("TEMP_CHECKIN_MEDIA_TOO_LARGE");
        assertThat(((AudioErrorResponse) tooLarge.getBody()).reason()).isEqualTo("FILE_TOO_LARGE");
        var missing = handler.missingPart(new MissingServletRequestPartException("file"), uploadRequest());
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(((AudioErrorResponse) missing.getBody()).reason()).isEqualTo("EMPTY_FILE");
    }

    @Test
    void keepsLegacySlotDiagnosticSegmentConsistentWithSubmissionId() {
        var request = new MockHttpServletRequest("PUT", "/sales-checkin/api/v1/submissions/"
                + SUBMISSION_ID + "/media/audio");
        handler.handle(TemporaryCheckinException.audioRejected(AudioRejectionReason.EMPTY_FILE), request);
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage()).contains("segmentId=" + SUBMISSION_ID);
    }

    @Test
    void excludesOtherRoutesMethodsAndInvalidPathValuesFromAudioDiagnostics() {
        for (var request : java.util.List.of(
                new MockHttpServletRequest("PUT", AUDIO_PATH.replace("audio/", "photos/")),
                new MockHttpServletRequest("DELETE", AUDIO_PATH),
                new MockHttpServletRequest("PUT", AUDIO_PATH.replace(SEGMENT_ID, "invalid-id-never-log")))) {
            var response = handler.handle(TemporaryCheckinException.badRequest("原业务错误"), request);
            assertThat(response.getBody()).isInstanceOf(ErrorResponse.class);
            assertThat(((ErrorResponse) response.getBody()).message()).isEqualTo("原业务错误");
        }
        assertThat(logs.list).isEmpty();
        var missing = new MissingServletRequestPartException("other-part");
        assertThatThrownBy(() -> handler.missingPart(missing, uploadRequest())).isSameAs(missing);
    }

    @Test
    void omitsUnsafeRequestIdInsteadOfLoggingControlCharactersOrRawHeaders() {
        RequestContext.set("unsafe\r\nnever-log-request-id", "zh-CN");
        var request = uploadRequest();
        request.addHeader("X-Request-Id", "never-log-header-fallback");
        var response = handler.handle(TemporaryCheckinException.audioRejected(AudioRejectionReason.READ_FAILED), request);
        assertThat(((AudioErrorResponse) response.getBody()).requestId()).isEqualTo("unavailable");
        assertThat(logs.list.getFirst().getFormattedMessage())
                .contains("requestId=unavailable", "reason=READ_FAILED")
                .doesNotContain("never-log", "\r", "\n");
    }

    private static MockHttpServletRequest uploadRequest() {
        return new MockHttpServletRequest("PUT", AUDIO_PATH);
    }
}
