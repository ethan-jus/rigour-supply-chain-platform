package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.ErrorResponse;
import com.rigour.shared.context.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** 临时打卡业务错误与Servlet媒体超限；multipart解析发生在路由绑定之前。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class TemporaryCheckinExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TemporaryCheckinExceptionHandler.class);
    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern AUDIO_UPLOAD_PATH = Pattern.compile(
            "^/sales-checkin/api/v1/submissions/(" + UUID_PATTERN + ")/media/audio(?:/("
                    + UUID_PATTERN + "))?$"
    );

    @ExceptionHandler(TemporaryCheckinException.class)
    ResponseEntity<?> handle(TemporaryCheckinException exception, HttpServletRequest request) {
        AudioUploadTarget target = audioUploadTarget(request);
        if (target != null) {
            String reason = exception.audioRejectionReason() == null
                    ? "BUSINESS_RULE" : exception.audioRejectionReason().name();
            return audioError(target, exception.status().value(), exception.code(),
                    exception.getMessage(), reason);
        }
        return ResponseEntity.status(exception.status())
                .body(new ErrorResponse(exception.code(), exception.getMessage()));
    }

    /** Servlet 解析阶段的超限也返回明确的不可盲目重试响应。 */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge(HttpServletRequest request) {
        AudioUploadTarget target = audioUploadTarget(request);
        if (target != null) {
            return audioError(target, 413, "TEMP_CHECKIN_MEDIA_TOO_LARGE",
                    TemporaryCheckinException.AudioRejectionReason.FILE_TOO_LARGE.message(), "FILE_TOO_LARGE");
        }
        return ResponseEntity.status(413).body(new ErrorResponse(
                "TEMP_CHECKIN_MEDIA_TOO_LARGE", "文件超过上传大小限制，请选择较小文件"));
    }

    /** 未携带 file 的请求在进入 Service 前失败；仅接管录音路由，其余沿用框架原处理。 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<?> missingPart(MissingServletRequestPartException exception, HttpServletRequest request)
            throws MissingServletRequestPartException {
        AudioUploadTarget target = audioUploadTarget(request);
        if (target == null || !"file".equals(exception.getRequestPartName())) throw exception;
        return audioError(target, 400, "TEMP_CHECKIN_BAD_REQUEST",
                TemporaryCheckinException.AudioRejectionReason.EMPTY_FILE.message(), "EMPTY_FILE");
    }

    private ResponseEntity<AudioErrorResponse> audioError(
            AudioUploadTarget target, int status, String code, String message, String reason) {
        String requestId = safeRequestId();
        // 不打印异常、原 URI、文件名、内容、请求头或原始客户端参数。
        log.warn("temporary_checkin_audio_rejected requestId={} submissionId={} segmentId={} status={} reason={} code={}",
                requestId, target.submissionId(), target.segmentId(), status, reason, code);
        return ResponseEntity.status(status).body(new AudioErrorResponse(code, message, reason, requestId));
    }

    private static AudioUploadTarget audioUploadTarget(HttpServletRequest request) {
        if (!"PUT".equals(request.getMethod())) return null;
        var matcher = AUDIO_UPLOAD_PATH.matcher(request.getRequestURI());
        if (!matcher.matches()) return null;
        // 旧 audio 单槽接口以 submissionId 作为 segmentId，与 Service 中的兼容约定一致。
        return new AudioUploadTarget(matcher.group(1), matcher.group(2) == null ? matcher.group(1) : matcher.group(2));
    }

    private static String safeRequestId() {
        String value = RequestContext.getRequestId();
        return value != null && value.matches("[A-Za-z0-9._:-]{1,128}") ? value : "unavailable";
    }

    record AudioErrorResponse(String code, String message, String reason, String requestId) { }
    private record AudioUploadTarget(String submissionId, String segmentId) { }
}
