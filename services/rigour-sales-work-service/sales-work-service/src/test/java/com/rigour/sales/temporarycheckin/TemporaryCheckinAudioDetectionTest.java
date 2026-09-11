package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TemporaryCheckinAudioDetectionTest {

    @Test
    void acceptsCommonPhoneAndMessagingAudioSignaturesWithoutTrustingMimeOrFilename() {
        assertDetected(bytes("ADIFdata"), "audio/aac", ".aac");
        assertDetected(new byte[] {0x56, (byte) 0xe0, 0x01}, "audio/aac", ".aac");
        assertDetected(bytes("fLaCdata"), "audio/flac", ".flac");
        assertDetected(bytes("caffdata"), "audio/x-caf", ".caf");
        assertDetected(bytes("FORM1234AIFF"), "audio/aiff", ".aiff");
        assertDetected(bytes("#!SILK_V3"), "audio/silk", ".silk");
        assertDetected(concat(new byte[] {0x02}, bytes("#!SILK_V3")), "audio/silk", ".silk");
        assertDetected(bytes("RF641234WAVE"), "audio/wav", ".wav");
    }

    @Test
    void acceptsAudioOnlyThreeGpAndMp4FromStructuredSoundHandlers() {
        assertDetected(isoAudio("3gp6"), "audio/3gpp", ".3gp");
        assertDetected(isoAudio("3g2a"), "audio/3gpp2", ".3g2");
        assertDetected(isoAudio("isom"), "audio/mp4", ".m4a");
    }

    @Test
    void recognizesSoundHandlerSplitAcrossStreamingChunks() {
        byte[] first = new byte[16 * 1024];
        byte[] prefix = isoPrefix("isom");
        System.arraycopy(prefix, 0, first, 0, prefix.length);
        byte[] handlerStart = new byte[] {0, 0, 0, 20, 'h', 'd', 'l', 'r'};
        System.arraycopy(handlerStart, 0, first, first.length - handlerStart.length, handlerStart.length);
        byte[] second = new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 's', 'o', 'u', 'n'};

        var probe = new TemporaryCheckinService.MediaSignatureProbe(true);
        probe.accept(first, first.length);
        probe.accept(second, second.length);
        TemporaryCheckinService.DetectedMedia detected = TemporaryCheckinService.detectAudio(probe);

        assertThat(detected.contentType()).isEqualTo("audio/mp4");
        assertThat(detected.extension()).isEqualTo(".m4a");
    }

    @Test
    void stillRejectsPicturesVideoTracksAndUnknownPayloads() {
        assertRejected(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0}, "IMAGE_FILE");
        assertRejected(concat(isoAudio("isom"), isoHandler("vide")), "VIDEO_TRACK");
        assertRejected(concat(isoPrefix("isom"), isoBox("mp4a"), isoBox("dvh1")), "NO_AUDIO_TRACK");
        assertRejected(concat(isoPrefix("isom"), isoBox("mp4a")), "NO_AUDIO_TRACK");
        assertRejected(concat(isoPrefix("isom"), isoBox("hdlr"), new byte[8], bytes("soun")), "NO_AUDIO_TRACK");
        assertRejected(concat(
                        new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3},
                        bytes("A_OPUS"), ebmlCodecId("V_MPEG4/ISO/AVC")), "VIDEO_TRACK");
        assertRejected(concat(
                        new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3},
                        bytes("A_OPUS"), ebmlCodecId("V_MPEGH/ISO/HEVC")), "VIDEO_TRACK");
        assertRejected(bytes("plain text is not audio"), "UNRECOGNIZED_FORMAT");
    }

    @Test
    void distinguishesMissingAndVideoTracksInRecognizedContainers() {
        assertRejected(isoPrefix("isom"), "NO_AUDIO_TRACK");
        assertRejected(bytes("OggSnot-an-audio-track"), "NO_AUDIO_TRACK");
        assertRejected(bytes("OggSOpusHeadtheora"), "VIDEO_TRACK");
        assertRejected(new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3}, "NO_AUDIO_TRACK");
        assertDetected(bytes("OggSOpusHead"), "audio/ogg", ".ogg");
    }

    @Test
    void scansLargeIsoPayloadWithoutMultiplyingWorkByEveryKnownCodec() {
        byte[] content = new byte[16 * 1024 * 1024];
        byte[] prefix = isoPrefix("isom");
        System.arraycopy(prefix, 0, content, 0, prefix.length);

        assertTimeout(Duration.ofSeconds(2), () -> {
            var probe = new TemporaryCheckinService.MediaSignatureProbe(true);
            probe.accept(content, content.length);
            assertThatThrownBy(() -> TemporaryCheckinService.detectAudio(probe))
                    .isInstanceOf(TemporaryCheckinException.class);
        });
    }

    @Test
    void doesNotTreatRawVideoPrefixInsideLargeEbmlAudioPayloadAsVideoTrack() {
        byte[] content = new byte[1024 * 1024];
        byte[] header = concat(
                new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3}, bytes("A_OPUS"));
        System.arraycopy(header, 0, content, 0, header.length);
        byte[] incidentalPayload = bytes("V_MPEG4/ISO/AVC");
        System.arraycopy(incidentalPayload, 0, content, 512 * 1024, incidentalPayload.length);

        assertDetected(content, "audio/webm", ".webm");
    }

    private static void assertDetected(byte[] content, String contentType, String extension) {
        TemporaryCheckinService.DetectedMedia detected = detect(content);
        assertThat(detected.contentType()).isEqualTo(contentType);
        assertThat(detected.extension()).isEqualTo(extension);
    }

    private static void assertRejected(byte[] content, String reason) {
        assertThatThrownBy(() -> detect(content)).isInstanceOfSatisfying(
                TemporaryCheckinException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(400);
                    assertThat(exception.code()).isEqualTo("TEMP_CHECKIN_BAD_REQUEST");
                    assertThat(exception.audioRejectionReason().name()).isEqualTo(reason);
                    assertThat(exception.getMessage()).contains("录音");
                });
    }

    private static TemporaryCheckinService.DetectedMedia detect(byte[] content) {
        var probe = new TemporaryCheckinService.MediaSignatureProbe(true);
        probe.accept(content, content.length);
        return TemporaryCheckinService.detectAudio(probe);
    }

    private static byte[] isoAudio(String brand) {
        return concat(isoPrefix(brand), isoHandler("soun"));
    }

    private static byte[] isoPrefix(String brand) {
        return concat(new byte[] {0, 0, 0, 12, 'f', 't', 'y', 'p'}, bytes(brand));
    }

    private static byte[] isoHandler(String handler) {
        return concat(new byte[] {0, 0, 0, 20, 'h', 'd', 'l', 'r', 0, 0, 0, 0,
                0, 0, 0, 0}, bytes(handler));
    }

    private static byte[] isoBox(String type) {
        return concat(new byte[] {0, 0, 0, 8}, bytes(type));
    }

    private static byte[] ebmlCodecId(String codecId) {
        byte[] value = bytes(codecId);
        if (value.length > 127) throw new IllegalArgumentException("test codec id too long");
        return concat(new byte[] {(byte) 0x86, (byte) (0x80 | value.length)}, value);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... values) {
        int size = 0;
        for (byte[] value : values) size += value.length;
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }
}
