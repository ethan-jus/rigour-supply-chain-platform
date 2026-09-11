package com.rigour.sales.infrastructure.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.rigour.sales.infrastructure.config.SalesRecordingProperties;
import com.rigour.sales.testing.RecordingMediaSamples;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class AacAdtsRecordingMediaVerifierTest {

    private final AacAdtsRecordingMediaVerifier verifier =
            new AacAdtsRecordingMediaVerifier(new SalesRecordingProperties());

    @Test
    void fixtureBytesRemainCompleteAndPinned() throws Exception {
        assertThat(RecordingMediaSamples.adts()).hasSize(RecordingMediaSamples.ADTS_SIZE);
        assertThat(sha256(RecordingMediaSamples.adts())).isEqualTo(RecordingMediaSamples.ADTS_SHA256);
        assertThat(RecordingMediaSamples.m4a()).hasSize(RecordingMediaSamples.M4A_SIZE);
        assertThat(sha256(RecordingMediaSamples.m4a())).isEqualTo(RecordingMediaSamples.M4A_SHA256);
        assertThat(RecordingMediaSamples.alternateM4a())
                .hasSize(RecordingMediaSamples.ALTERNATE_M4A_SIZE);
        assertThat(sha256(RecordingMediaSamples.alternateM4a()))
                .isEqualTo(RecordingMediaSamples.ALTERNATE_M4A_SHA256);
    }

    @Test
    void decodesEveryFrameFromRealAdtsBeforeVerification() {
        var result = verifier.verify("audio/aac", RecordingMediaSamples.adts());

        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.observedDurationMs()).isBetween(50L, 150L);
        assertThat(result.decodedContentHash()).startsWith("pcm-sha256-v1:").hasSize(78);
    }

    @Test
    void structurallyFramedFakeAdtsCannotClaimTrustedDecode() {
        var result = verifier.verify("audio/aac", adtsFrames(100, 4));

        assertThat(result.status()).isEqualTo("INVALID");
        assertThat(result.reason()).isEqualTo("AAC_DECODE_OUTPUT_INVALID");
    }

    @Test
    void rejectsTruncatedOrNonAdtsAac() {
        assertThat(verifier.verify("audio/aac", new byte[] {1, 2, 3}).status()).isEqualTo("INVALID");
        byte[] truncated = adtsFrames(1, 4);
        truncated[4] = 0x7f;
        assertThat(verifier.verify("audio/aac", truncated).status()).isEqualTo("INVALID");
    }

    @Test
    void acceptsId3PrefixedAdtsReturnedByMobileClients() {
        byte[] firstId3 = new byte[] {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 3, 1, 2, 3};
        byte[] secondId3 = new byte[] {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 3, 9, 8, 7};
        byte[] firstBytes = concat(firstId3, RecordingMediaSamples.adts());
        byte[] secondBytes = concat(secondId3, RecordingMediaSamples.adts());
        var result = verifier.verify("audio/aac", firstBytes);
        var samePcmDifferentMetadata = verifier.verify("audio/aac", secondBytes);

        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.detectedMediaType()).isEqualTo("audio/aac");
        assertThat(result.observedDurationMs()).isBetween(50L, 150L);
        assertThat(sha256(firstBytes)).isNotEqualTo(sha256(secondBytes));
        assertThat(samePcmDifferentMetadata.decodedContentHash())
                .isEqualTo(result.decodedContentHash());
    }

    @Test
    void decodesRealM4aAcrossUnreliableMimeTypes() {
        byte[] bytes = RecordingMediaSamples.m4a();
        String adtsContentHash = verifier.verify("audio/aac", RecordingMediaSamples.adts())
                .decodedContentHash();

        for (String mediaType : new String[] {
                "audio/aac", "audio/m4a", "audio/mp4", "application/octet-stream"}) {
            var result = verifier.verify(mediaType, bytes);
            assertThat(result.status()).as("%s: %s", mediaType, result).isEqualTo("VERIFIED");
            assertThat(result.detectedMediaType()).as(mediaType).isEqualTo("audio/m4a");
            assertThat(result.observedDurationMs()).as(mediaType).isBetween(50L, 150L);
            assertThat(result.decodedContentHash()).as(mediaType).isEqualTo(adtsContentHash);
        }
    }

    @Test
    void m4aMetadataChangesCannotChangeDecodedContentIdentity() throws Exception {
        byte[] original = RecordingMediaSamples.m4a();
        byte[] withFreeMetadata = concat(original, box("free", ascii("different-metadata")));

        var first = verifier.verify("audio/m4a", original);
        var second = verifier.verify("application/octet-stream", withFreeMetadata);

        assertThat(sha256(original)).isNotEqualTo(sha256(withFreeMetadata));
        assertThat(first.status()).isEqualTo("VERIFIED");
        assertThat(second.status()).isEqualTo("VERIFIED");
        assertThat(second.decodedContentHash()).isEqualTo(first.decodedContentHash());
        var genuinelyDifferentAudio = verifier.verify("audio/m4a", RecordingMediaSamples.alternateM4a());
        assertThat(genuinelyDifferentAudio.status()).isEqualTo("VERIFIED");
        assertThat(genuinelyDifferentAudio.decodedContentHash())
                .isNotEqualTo(first.decodedContentHash());
    }

    @Test
    void everyAdtsPayloadMustDecodeIncludingFirstMiddleAndLast() {
        List<Integer> frameOffsets = adtsFrameOffsets(RecordingMediaSamples.adts());
        assertThat(frameOffsets).hasSizeGreaterThanOrEqualTo(3);

        for (int frameIndex : new int[] {0, frameOffsets.size() / 2, frameOffsets.size() - 1}) {
            var result = verifier.verify("audio/aac", zeroFramePayload(
                    RecordingMediaSamples.adts(), frameIndex));
            assertThat(result.status()).as("frame %s: %s", frameIndex, result)
                    .isEqualTo("INVALID");
        }
    }

    @Test
    void permitsOneBoundedAacFramePastTenMinutesForCodecPadding() {
        byte[] bytes = repeatFirstAdtsFrame(25_840);

        var result = verifier.verify("audio/aac", bytes);

        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.observedDurationMs()).isGreaterThan(600_000L).isLessThanOrEqualTo(601_000L);
    }

    @Test
    void rejectsDecodedDurationBeyondTheBoundedCodecPaddingAllowance() {
        byte[] bytes = repeatFirstAdtsFrame(25_883);

        var result = verifier.verify("audio/aac", bytes);

        assertThat(result.status()).isEqualTo("INVALID");
        assertThat(result.reason()).isEqualTo("AAC_DECODE_LIMIT_EXCEEDED");
    }

    @Test
    void everyM4aSampleMustDecodeIncludingFirstMiddleAndLast() {
        int sampleCount = m4aSampleSizes(RecordingMediaSamples.m4a()).size();
        assertThat(sampleCount).isGreaterThanOrEqualTo(3);

        for (int sampleIndex : new int[] {0, sampleCount / 2, sampleCount - 1}) {
            var result = verifier.verify("audio/m4a", zeroM4aSample(
                    RecordingMediaSamples.m4a(), sampleIndex));
            assertThat(result.status()).as("sample %s: %s", sampleIndex, result)
                    .isEqualTo("INVALID");
        }
    }

    @Test
    void adtsCrcAndMultipleRawBlockClaimsNeverBecomeVerified() {
        byte[] crcClaimWithoutCrc = RecordingMediaSamples.adts();
        crcClaimWithoutCrc[1] &= (byte) 0xfe;
        var crc = verifier.verify("audio/aac", crcClaimWithoutCrc);
        assertThat(crc.status()).isEqualTo("STRUCTURALLY_VALID");
        assertThat(crc.reason()).isEqualTo("AAC_ADTS_CRC_UNSUPPORTED");

        byte[] multipleRawBlocks = RecordingMediaSamples.adts();
        multipleRawBlocks[6] = (byte) ((multipleRawBlocks[6] & 0xfc) | 0x03);
        var multiple = verifier.verify("audio/aac", multipleRawBlocks);
        assertThat(multiple.status()).isEqualTo("STRUCTURALLY_VALID");
        assertThat(multiple.reason()).isEqualTo("AAC_ADTS_MULTIPLE_RAW_BLOCKS_UNSUPPORTED");
    }

    @Test
    void adtsConfigurationMustRemainStableAcrossEveryFrame() {
        byte[] bytes = RecordingMediaSamples.adts();
        int secondFrame = adtsFrameOffsets(bytes).get(1);
        bytes[secondFrame + 2] = (byte) ((bytes[secondFrame + 2] & 0xc3) | (3 << 2));

        var result = verifier.verify("audio/aac", bytes);

        assertThat(result.status()).isEqualTo("STRUCTURALLY_VALID");
        assertThat(result.reason()).isEqualTo("AAC_ADTS_CONFIG_CHANGE_UNSUPPORTED");
    }

    @Test
    void rejectsZeroFilledM4aPayloadEvenWhenContainerTablesLookCoherent() {
        var result = verifier.verify("audio/aac", m4aFile(44_100, 1_507, 1_024, 2));

        assertThat(result.status()).isEqualTo("INVALID");
        assertThat(result.reason()).isEqualTo("AAC_PAYLOAD_EMPTY");
    }

    @Test
    void reportsStableReasonForMalformedIsoBmff() {
        byte[] malformed = box("ftyp", concat(ascii("M4A "), new byte[4], ascii("isom")));

        var result = verifier.verify("audio/aac", malformed);

        assertThat(result.status()).isEqualTo("INVALID");
        assertThat(result.reason()).isEqualTo("AAC_ISO_BMFF_STRUCTURE_INVALID");
    }

    @Test
    void leavesOtherMediaTypesForACompatibleVerifier() {
        assertThat(verifier.verify("audio/mpeg", new byte[] {1, 2, 3}).status())
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

    private static byte[] m4aFile(int timescale, int sampleCount, int sampleDelta, int sampleSize) {
        int duration = sampleCount * sampleDelta;
        byte[] ftyp = box("ftyp", concat(ascii("M4A "), new byte[4], ascii("isom"), ascii("mp42")));
        byte[] mdhdPayload = ByteBuffer.allocate(24)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(timescale)
                .putInt(duration)
                .putInt(0)
                .array();
        byte[] hdlrPayload = concat(new byte[8], ascii("soun"), new byte[12]);
        byte[] mp4a = box("mp4a", new byte[28]);
        byte[] stsdPayload = concat(new byte[4], ByteBuffer.allocate(4).putInt(1).array(), mp4a);
        byte[] sttsPayload = ByteBuffer.allocate(16)
                .putInt(0).putInt(1).putInt(sampleCount).putInt(sampleDelta).array();
        byte[] stszPayload = ByteBuffer.allocate(12)
                .putInt(0).putInt(sampleSize).putInt(sampleCount).array();
        byte[] stbl = box("stbl", concat(
                box("stsd", stsdPayload), box("stts", sttsPayload), box("stsz", stszPayload)));
        byte[] minf = box("minf", stbl);
        byte[] mdia = box("mdia", concat(box("mdhd", mdhdPayload), box("hdlr", hdlrPayload), minf));
        byte[] moov = box("moov", box("trak", mdia));
        return concat(ftyp, box("mdat", new byte[sampleCount * sampleSize]), moov);
    }

    private static byte[] box(String type, byte[] payload) {
        return concat(ByteBuffer.allocate(4).putInt(payload.length + 8).array(), ascii(type), payload);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) output.writeBytes(part);
        return output.toByteArray();
    }

    private static List<Integer> adtsFrameOffsets(byte[] bytes) {
        List<Integer> offsets = new ArrayList<>();
        int offset = 0;
        while (offset < bytes.length) {
            offsets.add(offset);
            int length = ((bytes[offset + 3] & 0x03) << 11)
                    | ((bytes[offset + 4] & 0xff) << 3)
                    | ((bytes[offset + 5] & 0xff) >>> 5);
            offset += length;
        }
        assertThat(offset).isEqualTo(bytes.length);
        return offsets;
    }

    private static byte[] zeroFramePayload(byte[] original, int frameIndex) {
        byte[] bytes = original.clone();
        int offset = adtsFrameOffsets(bytes).get(frameIndex);
        int length = ((bytes[offset + 3] & 0x03) << 11)
                | ((bytes[offset + 4] & 0xff) << 3)
                | ((bytes[offset + 5] & 0xff) >>> 5);
        int headerLength = (bytes[offset + 1] & 0x01) == 1 ? 7 : 9;
        Arrays.fill(bytes, offset + headerLength, offset + length, (byte) 0);
        return bytes;
    }

    private static byte[] repeatFirstAdtsFrame(int frameCount) {
        byte[] sample = RecordingMediaSamples.adts();
        int frameLength = adtsFrameOffsets(sample).get(1);
        byte[] repeated = new byte[Math.multiplyExact(frameLength, frameCount)];
        for (int index = 0; index < frameCount; index++) {
            System.arraycopy(sample, 0, repeated, index * frameLength, frameLength);
        }
        return repeated;
    }

    private static byte[] zeroM4aSample(byte[] original, int sampleIndex) {
        byte[] bytes = original.clone();
        List<Integer> sizes = m4aSampleSizes(bytes);
        int stcoType = indexOf(bytes, ascii("stco"));
        assertThat(stcoType).isGreaterThanOrEqualTo(4);
        assertThat(ByteBuffer.wrap(bytes).getInt(stcoType + 8)).isEqualTo(1);
        int sampleOffset = ByteBuffer.wrap(bytes).getInt(stcoType + 12);
        for (int index = 0; index < sampleIndex; index++) sampleOffset += sizes.get(index);
        Arrays.fill(bytes, sampleOffset, sampleOffset + sizes.get(sampleIndex), (byte) 0);
        return bytes;
    }

    private static List<Integer> m4aSampleSizes(byte[] bytes) {
        int stszType = indexOf(bytes, ascii("stsz"));
        assertThat(stszType).isGreaterThanOrEqualTo(4);
        int fixedSize = ByteBuffer.wrap(bytes).getInt(stszType + 8);
        int count = ByteBuffer.wrap(bytes).getInt(stszType + 12);
        assertThat(fixedSize).isZero();
        assertThat(count).isBetween(1, 100);
        List<Integer> sizes = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            sizes.add(ByteBuffer.wrap(bytes).getInt(stszType + 16 + index * 4));
        }
        return sizes;
    }

    private static int indexOf(byte[] bytes, byte[] pattern) {
        for (int offset = 0; offset <= bytes.length - pattern.length; offset++) {
            boolean matches = true;
            for (int index = 0; index < pattern.length; index++) {
                if (bytes[offset + index] != pattern[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) return offset;
        }
        return -1;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }
}
