package com.rigour.sales.application.port.out;

/** 从服务端实际收到的音频字节核验媒体结构和时长，避免信任客户端自报时长。 */
public interface RecordingMediaVerifier {

    Verification verify(String mediaType, byte[] bytes);

    record Verification(String status, Long observedDurationMs, String reason,
                        String detectedMediaType, String decodedContentHash) {

        public static Verification verified(long durationMs, String decodedContentHash) {
            return verified(durationMs, "audio/aac", decodedContentHash);
        }

        public static Verification verified(long durationMs, String detectedMediaType,
                                            String decodedContentHash) {
            if (decodedContentHash == null || decodedContentHash.isBlank()) {
                throw new IllegalArgumentException("VERIFIED必须包含解码内容指纹");
            }
            return new Verification("VERIFIED", durationMs, null, detectedMediaType,
                    decodedContentHash);
        }

        public static Verification structurallyValid(long observedDurationMs, String detectedMediaType) {
            return structurallyValid(observedDurationMs, detectedMediaType, "AAC_DECODE_NOT_TRUSTED");
        }

        public static Verification structurallyValid(long observedDurationMs, String detectedMediaType,
                                                       String reason) {
            return new Verification("STRUCTURALLY_VALID", observedDurationMs,
                    reason, detectedMediaType, null);
        }

        public static Verification unsupported() {
            return new Verification("UNSUPPORTED", null, "MEDIA_TYPE_UNSUPPORTED", null, null);
        }

        public static Verification invalid(String reason) {
            return new Verification("INVALID", null, reason, null, null);
        }
    }
}
