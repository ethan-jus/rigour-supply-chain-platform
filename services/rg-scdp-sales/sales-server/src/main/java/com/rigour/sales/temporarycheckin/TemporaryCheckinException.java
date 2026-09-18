package com.rigour.sales.temporarycheckin;

import org.springframework.http.HttpStatus;

/** 临时表单边界的稳定 HTTP 错误，避免泄露 SQL、对象存储和密钥细节。 */
final class TemporaryCheckinException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final AudioRejectionReason audioRejectionReason;

    TemporaryCheckinException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    private TemporaryCheckinException(
            HttpStatus status, String code, String message, AudioRejectionReason audioRejectionReason) {
        super(message);
        this.status = status;
        this.code = code;
        this.audioRejectionReason = audioRejectionReason;
    }

    HttpStatus status() { return status; }
    String code() { return code; }
    AudioRejectionReason audioRejectionReason() { return audioRejectionReason; }

    /** 仅记录服务端判断出的分类；不带文件名、内容、客户端元数据或存储异常。 */
    enum AudioRejectionReason {
        EMPTY_FILE("录音文件为空，请重新选择完整录音或重新录制；可先提交拜访"),
        READ_FAILED("录音文件读取失败，请保留原文件并重新选择后上传；可先提交拜访"),
        FILE_TOO_LARGE("录音文件超过大小限制，请选择较小文件；重试同一文件无效，可先提交拜访"),
        IMAGE_FILE("所选文件是图片，不是录音；录音为选填，可删除后继续提交"),
        NO_AUDIO_TRACK("未识别到有效音频轨道，文件可能不完整；请重新选择完整录音或重新录制，可先提交拜访"),
        VIDEO_TRACK("所选文件含视频轨道，请选择纯录音文件；可先提交拜访"),
        UNRECOGNIZED_FORMAT("无法识别录音格式或文件不完整，请重新选择完整录音或重新录制；可先提交拜访"),
        SEGMENT_LIMIT("本次拜访录音分段过多，请删除无效片段后重试"),
        TOTAL_SIZE_LIMIT("本次拜访录音总量过大，请删除无效片段后重试");

        private final String message;

        AudioRejectionReason(String message) { this.message = message; }
        String message() { return message; }
    }

    static TemporaryCheckinException audioRejected(AudioRejectionReason reason) {
        return new TemporaryCheckinException(
                HttpStatus.BAD_REQUEST, "TEMP_CHECKIN_BAD_REQUEST", reason.message(), reason);
    }

    static TemporaryCheckinException badRequest(String message) {
        return new TemporaryCheckinException(HttpStatus.BAD_REQUEST, "TEMP_CHECKIN_BAD_REQUEST", message);
    }

    static TemporaryCheckinException notFound(String message) {
        return new TemporaryCheckinException(HttpStatus.NOT_FOUND, "TEMP_CHECKIN_NOT_FOUND", message);
    }

    static TemporaryCheckinException conflict(String message) {
        return new TemporaryCheckinException(HttpStatus.CONFLICT, "TEMP_CHECKIN_CONFLICT", message);
    }

    static TemporaryCheckinException forbidden(String message) {
        return new TemporaryCheckinException(HttpStatus.FORBIDDEN, "TEMP_CHECKIN_KEY_INVALID", message);
    }

    static TemporaryCheckinException unauthorizedIdentity(String message) {
        return new TemporaryCheckinException(
                HttpStatus.UNAUTHORIZED, "TEMP_CHECKIN_IDENTITY_REQUIRED", message);
    }

    static TemporaryCheckinException forbiddenIdentity(String message) {
        return new TemporaryCheckinException(
                HttpStatus.FORBIDDEN, "TEMP_CHECKIN_IDENTITY_INVALID", message);
    }

    static TemporaryCheckinException adminForbidden(String message) {
        return new TemporaryCheckinException(HttpStatus.FORBIDDEN, "TEMP_CHECKIN_ADMIN_FORBIDDEN", message);
    }

    static TemporaryCheckinException adminUnauthorized(String message) {
        return new TemporaryCheckinException(
                HttpStatus.UNAUTHORIZED, "TEMP_CHECKIN_ADMIN_UNAUTHORIZED", message);
    }

    static TemporaryCheckinException passwordChangeRequired(String message) {
        return new TemporaryCheckinException(
                HttpStatus.FORBIDDEN, "TEMP_CHECKIN_PASSWORD_CHANGE_REQUIRED", message);
    }

    static TemporaryCheckinException loginLocked(String message) {
        return new TemporaryCheckinException(HttpStatus.TOO_MANY_REQUESTS, "TEMP_CHECKIN_LOGIN_LOCKED", message);
    }

    static TemporaryCheckinException storage(String message) {
        return new TemporaryCheckinException(HttpStatus.SERVICE_UNAVAILABLE, "TEMP_CHECKIN_STORAGE_FAILED", message);
    }
}
