package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 临时打卡业务错误与Servlet媒体超限；multipart解析发生在路由绑定之前。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class TemporaryCheckinExceptionHandler {

    @ExceptionHandler(TemporaryCheckinException.class)
    ResponseEntity<ErrorResponse> handle(TemporaryCheckinException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ErrorResponse(exception.code(), exception.getMessage()));
    }

    /** Servlet 解析阶段的超限也返回明确的不可盲目重试响应。 */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> tooLarge() {
        return ResponseEntity.status(413).body(new ErrorResponse(
                "TEMP_CHECKIN_MEDIA_TOO_LARGE", "文件超过上传大小限制，请选择较小文件"));
    }

}
