package com.rigour.sales.infrastructure.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class AacAdtsRecordingMediaVerifierTest {

    private final AacAdtsRecordingMediaVerifier verifier = new AacAdtsRecordingMediaVerifier();

    @Test
    void calculatesDurationFromCompleteAdtsFrames() {
        var result = verifier.verify("audio/aac", adtsFrames(100, 4));

        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.verifiedDurationMs()).isBetween(2_320L, 2_324L);
    }

    @Test
    void rejectsTruncatedOrNonAdtsAac() {
        assertThat(verifier.verify("audio/aac", new byte[] {1, 2, 3}).status()).isEqualTo("INVALID");
        byte[] truncated = adtsFrames(1, 4);
        truncated[4] = 0x7f;
        assertThat(verifier.verify("audio/aac", truncated).status()).isEqualTo("INVALID");
    }

    @Test
    void leavesOtherMediaTypesForACompatibleVerifier() {
        assertThat(verifier.verify("audio/m4a", new byte[] {1, 2, 3}).status())
                .isEqualTo("UNSUPPORTED");
    }

    /** sampleRateIndex 4 = 44.1kHz, AAC-LC, stereo, 2-byte payload. */
    static byte[] adtsFrames(int frameCount, int sampleRateIndex) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(frameCount * 9);
        int frameLength = 9;
        for (int index = 0; index < frameCount; index++) {
            output.write(0xff);
            output.write(0xf1);
            output.write((1 << 6) | (sampleRateIndex << 2));
            output.write((2 << 6) | ((frameLength >>> 11) & 0x03));
            output.write((frameLength >>> 3) & 0xff);
            output.write(((frameLength & 0x07) << 5) | 0x1f);
            output.write(0xfc);
            output.write(index & 0xff);
            output.write((index >>> 8) & 0xff);
        }
        return output.toByteArray();
    }
}
