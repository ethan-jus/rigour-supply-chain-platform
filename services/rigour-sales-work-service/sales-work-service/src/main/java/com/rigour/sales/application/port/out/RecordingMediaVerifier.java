package com.rigour.sales.application.port.out;

/** 从服务端实际收到的音频字节核验媒体结构和时长，避免信任客户端自报时长。 */
public interface RecordingMediaVerifier {

    Verification verify(String mediaType, byte[] bytes);

    record Verification(String status, Long verifiedDurationMs, String reason) {

        public static Verification verified(long durationMs) {
            return new Verification("VERIFIED", durationMs, null);
        }

        public static Verification unsupported() {
            return new Verification("UNSUPPORTED", null, "MEDIA_TYPE_UNSUPPORTED");
        }

        public static Verification invalid(String reason) {
            return new Verification("INVALID", null, reason);
        }
    }
}
