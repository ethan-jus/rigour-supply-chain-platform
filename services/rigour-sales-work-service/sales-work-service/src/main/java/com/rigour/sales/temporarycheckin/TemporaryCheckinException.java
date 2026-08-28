package com.rigour.sales.temporarycheckin;

import org.springframework.http.HttpStatus;

/** 临时表单边界的稳定 HTTP 错误，避免泄露 SQL、对象存储和密钥细节。 */
final class TemporaryCheckinException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    TemporaryCheckinException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() { return status; }
    String code() { return code; }

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
