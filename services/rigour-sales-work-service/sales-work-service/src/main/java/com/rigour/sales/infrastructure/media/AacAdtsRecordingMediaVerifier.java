package com.rigour.sales.infrastructure.media;

import com.rigour.sales.application.port.out.RecordingMediaVerifier;
import org.springframework.stereotype.Component;

/** 校验飞书 RecorderManager 产生的 ADTS AAC 帧，并按帧采样数计算服务端可信时长。 */
@Component
public class AacAdtsRecordingMediaVerifier implements RecordingMediaVerifier {

    private static final int[] SAMPLE_RATES = {
            96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000,
            22_050, 16_000, 12_000, 11_025, 8_000, 7_350
    };

    @Override
    public Verification verify(String mediaType, byte[] bytes) {
        if (!"audio/aac".equals(mediaType)) return Verification.unsupported();
        if (bytes == null || bytes.length < 7) return Verification.invalid("AAC_EMPTY_OR_TRUNCATED");
        int offset = 0;
        long durationMicros = 0;
        int frameCount = 0;
        while (offset < bytes.length) {
            if (bytes.length - offset < 7 || !isAdtsSync(bytes, offset)) {
                return Verification.invalid("AAC_ADTS_SYNC_INVALID");
            }
            int sampleRateIndex = (unsigned(bytes[offset + 2]) >>> 2) & 0x0f;
            if (sampleRateIndex >= SAMPLE_RATES.length) {
                return Verification.invalid("AAC_SAMPLE_RATE_INVALID");
            }
            int frameLength = ((unsigned(bytes[offset + 3]) & 0x03) << 11)
                    | (unsigned(bytes[offset + 4]) << 3)
                    | ((unsigned(bytes[offset + 5]) >>> 5) & 0x07);
            int headerLength = (unsigned(bytes[offset + 1]) & 0x01) == 1 ? 7 : 9;
            if (frameLength < headerLength || frameLength > bytes.length - offset) {
                return Verification.invalid("AAC_FRAME_LENGTH_INVALID");
            }
            int rawDataBlocks = unsigned(bytes[offset + 6]) & 0x03;
            long samples = 1024L * (rawDataBlocks + 1L);
            durationMicros += Math.round(samples * 1_000_000d / SAMPLE_RATES[sampleRateIndex]);
            if (durationMicros < 0) return Verification.invalid("AAC_DURATION_OVERFLOW");
            frameCount++;
            offset += frameLength;
        }
        if (frameCount == 0 || offset != bytes.length) return Verification.invalid("AAC_NO_COMPLETE_FRAME");
        return Verification.verified(Math.max(1L, Math.round(durationMicros / 1_000d)));
    }

    private static boolean isAdtsSync(byte[] bytes, int offset) {
        return unsigned(bytes[offset]) == 0xff && (unsigned(bytes[offset + 1]) & 0xf6) == 0xf0;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }
}
