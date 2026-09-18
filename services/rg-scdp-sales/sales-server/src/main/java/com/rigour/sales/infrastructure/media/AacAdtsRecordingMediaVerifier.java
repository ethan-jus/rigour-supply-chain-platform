package com.rigour.sales.infrastructure.media;

import com.rigour.sales.application.port.out.RecordingMediaVerifier;
import com.rigour.sales.infrastructure.config.SalesRecordingProperties;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import net.sourceforge.jaad.aac.AACException;
import org.jcodec.codecs.aac.AACDecoder;
import org.jcodec.codecs.aac.ADTSParser;
import org.jcodec.common.AudioFormat;
import org.jcodec.common.Codec;
import org.jcodec.common.io.ByteBufferSeekableByteChannel;
import org.jcodec.common.model.AudioBuffer;
import org.jcodec.common.model.Packet;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.springframework.stereotype.Component;

/**
 * 校验飞书 RecorderManager 产生的 AAC，兼容 ADTS、带 ID3 前缀的 ADTS 和
 * ISO-BMFF/M4A 封装。只有结构校验及每个 AAC 帧实际解码都成功才签发 VERIFIED；
 * 解码器不支持的合法变体保持待核验，明确损坏的帧拒绝接收。
 */
@Component
public class AacAdtsRecordingMediaVerifier implements RecordingMediaVerifier {

    private static final int[] SAMPLE_RATES = {
            96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000,
            22_050, 16_000, 12_000, 11_025, 8_000, 7_350
    };
    private static final Set<String> ISO_BMFF_AUDIO_BRANDS = Set.of(
            "M4A ", "M4B ", "isom", "iso2", "mp41", "mp42", "qt  ");
    private static final Set<String> PROBE_MEDIA_TYPES = Set.of(
            "audio/aac", "audio/m4a", "audio/mp4", "application/octet-stream");
    private static final int MAX_DECODED_FRAMES = 60_000;
    private static final int MAX_PCM_BYTES_PER_FRAME = 64 * 1024;
    private static final long MAX_CLIP_DURATION_MS = 600_000L;
    /**
     * AAC encoders may emit priming and a padded final access unit around an exact client cut.
     * This fixed resource-only allowance is deliberately not trusted as recorded business time.
     */
    private static final long MAX_CODEC_PADDING_MS = 1_000L;
    private static final long MAX_DECODED_DURATION_MS =
            MAX_CLIP_DURATION_MS + MAX_CODEC_PADDING_MS;

    private final long maxInputBytes;

    public AacAdtsRecordingMediaVerifier(SalesRecordingProperties properties) {
        this.maxInputBytes = properties.getMaxClipBytes();
    }

    @Override
    public Verification verify(String mediaType, byte[] bytes) {
        if (!PROBE_MEDIA_TYPES.contains(mediaType)) return Verification.unsupported();
        if (bytes == null || bytes.length < 7) return Verification.invalid("AAC_EMPTY_OR_TRUNCATED");
        if (maxInputBytes <= 0 || bytes.length > maxInputBytes) {
            return Verification.invalid("AAC_INPUT_LIMIT_EXCEEDED");
        }
        int adtsOffset = adtsOffset(bytes);
        if (adtsOffset >= 0) return verifyAdts(bytes, adtsOffset);
        if (looksLikeIsoBmff(bytes)) return verifyIsoBmff(bytes);
        return Verification.invalid("AAC_CONTAINER_UNSUPPORTED");
    }

    private Verification verifyAdts(byte[] bytes, int offset) {
        int firstFrameOffset = offset;
        long totalSamples = 0;
        int frameCount = 0;
        Integer expectedProfile = null;
        Integer expectedSampleRateIndex = null;
        Integer expectedChannelConfig = null;
        boolean unsupportedCrc = false;
        boolean unsupportedMultipleRawBlocks = false;
        boolean unsupportedConfigChange = false;
        boolean hasNonZeroPayload = false;
        while (offset < bytes.length) {
            if (bytes.length - offset < 7 || !isAdtsSync(bytes, offset)) {
                return Verification.invalid("AAC_ADTS_SYNC_INVALID");
            }
            int sampleRateIndex = (unsigned(bytes[offset + 2]) >>> 2) & 0x0f;
            if (sampleRateIndex >= SAMPLE_RATES.length) {
                return Verification.invalid("AAC_SAMPLE_RATE_INVALID");
            }
            int profile = (unsigned(bytes[offset + 2]) >>> 6) & 0x03;
            int channelConfig = ((unsigned(bytes[offset + 2]) & 0x01) << 2)
                    | ((unsigned(bytes[offset + 3]) >>> 6) & 0x03);
            if (channelConfig <= 0) return Verification.invalid("AAC_CHANNEL_CONFIG_INVALID");
            if (expectedProfile == null) {
                expectedProfile = profile;
                expectedSampleRateIndex = sampleRateIndex;
                expectedChannelConfig = channelConfig;
            } else if (profile != expectedProfile || sampleRateIndex != expectedSampleRateIndex
                    || channelConfig != expectedChannelConfig) {
                unsupportedConfigChange = true;
            }
            int frameLength = ((unsigned(bytes[offset + 3]) & 0x03) << 11)
                    | (unsigned(bytes[offset + 4]) << 3)
                    | ((unsigned(bytes[offset + 5]) >>> 5) & 0x07);
            int headerLength = (unsigned(bytes[offset + 1]) & 0x01) == 1 ? 7 : 9;
            unsupportedCrc |= headerLength == 9;
            if (frameLength < headerLength || frameLength > bytes.length - offset) {
                return Verification.invalid("AAC_FRAME_LENGTH_INVALID");
            }
            hasNonZeroPayload |= containsNonZero(bytes, offset + headerLength, offset + frameLength);
            int rawDataBlocks = unsigned(bytes[offset + 6]) & 0x03;
            unsupportedMultipleRawBlocks |= rawDataBlocks != 0;
            long samples = 1024L * (rawDataBlocks + 1L);
            try {
                totalSamples = Math.addExact(totalSamples, samples);
            } catch (ArithmeticException error) {
                return Verification.invalid("AAC_DURATION_OVERFLOW");
            }
            frameCount++;
            long durationMs = durationMs(totalSamples, SAMPLE_RATES[sampleRateIndex]);
            if (frameCount > MAX_DECODED_FRAMES || durationMs > MAX_DECODED_DURATION_MS) {
                return Verification.invalid("AAC_DECODE_LIMIT_EXCEEDED");
            }
            offset += frameLength;
        }
        if (frameCount == 0 || offset != bytes.length) return Verification.invalid("AAC_NO_COMPLETE_FRAME");
        if (!hasNonZeroPayload) return Verification.invalid("AAC_PAYLOAD_EMPTY");
        long structuralDurationMs = durationMs(totalSamples, SAMPLE_RATES[expectedSampleRateIndex]);
        if (unsupportedCrc) {
            return Verification.structurallyValid(structuralDurationMs, "audio/aac",
                    "AAC_ADTS_CRC_UNSUPPORTED");
        }
        if (unsupportedMultipleRawBlocks) {
            return Verification.structurallyValid(structuralDurationMs, "audio/aac",
                    "AAC_ADTS_MULTIPLE_RAW_BLOCKS_UNSUPPORTED");
        }
        if (unsupportedConfigChange) {
            return Verification.structurallyValid(structuralDurationMs, "audio/aac",
                    "AAC_ADTS_CONFIG_CHANGE_UNSUPPORTED");
        }
        return decodeAdts(bytes, firstFrameOffset, frameCount, structuralDurationMs);
    }

    private Verification verifyIsoBmff(byte[] bytes) {
        int cursor = 0;
        Box ftyp = null;
        Box moov = null;
        long mediaDataBytes = 0;
        boolean hasNonZeroMediaData = false;
        while (cursor < bytes.length) {
            Box box = readBox(bytes, cursor, bytes.length);
            if (box == null) return Verification.invalid("AAC_ISO_BMFF_STRUCTURE_INVALID");
            if ("ftyp".equals(box.type())) ftyp = box;
            if ("moov".equals(box.type())) moov = box;
            if ("mdat".equals(box.type())) {
                mediaDataBytes += box.end() - box.contentStart();
                hasNonZeroMediaData |= containsNonZero(bytes, box.contentStart(), box.end());
            }
            cursor = box.end();
        }
        if (ftyp == null || !hasSupportedBrand(bytes, ftyp)) {
            return Verification.invalid("AAC_ISO_BMFF_BRAND_UNSUPPORTED");
        }
        if (moov == null || mediaDataBytes <= 0) {
            return Verification.invalid("AAC_ISO_BMFF_STRUCTURE_INVALID");
        }
        if (!hasNonZeroMediaData) return Verification.invalid("AAC_PAYLOAD_EMPTY");
        Long durationMs = findAacAudioDuration(bytes, moov, mediaDataBytes);
        if (durationMs == null || durationMs <= 0) {
            return Verification.invalid("AAC_ISO_BMFF_AUDIO_TRACK_INVALID");
        }
        if (durationMs > MAX_DECODED_DURATION_MS) {
            return Verification.invalid("AAC_DECODE_LIMIT_EXCEEDED");
        }
        return decodeIsoBmff(bytes, durationMs);
    }

    private Verification decodeAdts(byte[] bytes, int offset, int expectedFrameCount,
                                    long structuralDurationMs) {
        AACDecoder decoder = null;
        DecodeAccumulator decoded = new DecodeAccumulator();
        ByteBuffer pcmScratch = ByteBuffer.allocate(MAX_PCM_BYTES_PER_FRAME);
        while (offset < bytes.length) {
            int expectedFrameLength = ((unsigned(bytes[offset + 3]) & 0x03) << 11)
                    | (unsigned(bytes[offset + 4]) << 3)
                    | ((unsigned(bytes[offset + 5]) >>> 5) & 0x07);
            int expectedHeaderLength = (unsigned(bytes[offset + 1]) & 0x01) == 1 ? 7 : 9;
            ADTSParser.Header header;
            ByteBuffer candidate = ByteBuffer.wrap(bytes, offset, bytes.length - offset).slice();
            try {
                header = ADTSParser.read(candidate);
            } catch (RuntimeException error) {
                return Verification.structurallyValid(structuralDurationMs, "audio/aac",
                        "AAC_DECODER_UNSUPPORTED");
            }
            if (header == null || header.getSize() != expectedFrameLength
                    || candidate.position() != expectedHeaderLength
                    || header.getSize() <= candidate.position()
                    || header.getSize() > bytes.length - offset) {
                return Verification.invalid("AAC_ADTS_DECODE_MAPPING_INVALID");
            }
            if (decoder == null) {
                try {
                    decoder = new AACDecoder(ADTSParser.adtsToStreamInfo(header));
                } catch (AACException | RuntimeException error) {
                    return Verification.structurallyValid(structuralDurationMs, "audio/aac",
                            "AAC_DECODER_UNSUPPORTED");
                }
            }
            int headerBytes = candidate.position();
            ByteBuffer payload = ByteBuffer.wrap(bytes, offset + headerBytes,
                    header.getSize() - headerBytes).slice();
            try {
                pcmScratch.clear();
                if (!decoded.accept(decoder.decodeFrame(payload, pcmScratch))) {
                    return Verification.invalid("AAC_DECODE_OUTPUT_INVALID");
                }
            } catch (IOException | RuntimeException error) {
                return Verification.invalid("AAC_FRAME_DECODE_FAILED");
            }
            offset += header.getSize();
        }
        if (decoded.frameCount() != expectedFrameCount) {
            return Verification.invalid("AAC_DECODE_FRAME_COUNT_MISMATCH");
        }
        if (!durationMatches(structuralDurationMs, decoded.durationMs())) {
            return Verification.invalid("AAC_DECODE_DURATION_MISMATCH");
        }
        return Verification.verified(decoded.durationMs(), "audio/aac", decoded.contentHash());
    }

    private Verification decodeIsoBmff(byte[] bytes, long structuralDurationMs) {
        MP4Demuxer demuxer;
        try {
            demuxer = MP4Demuxer.createMP4Demuxer(
                    ByteBufferSeekableByteChannel.readFromByteBuffer(ByteBuffer.wrap(bytes)));
        } catch (IOException | RuntimeException error) {
            return Verification.structurallyValid(structuralDurationMs, "audio/m4a",
                    "AAC_DECODER_UNSUPPORTED");
        }
        try (demuxer) {
            var tracks = demuxer.getAudioTracks();
            if (tracks.size() != 1 || tracks.getFirst().getMeta().getCodec() != Codec.AAC) {
                return Verification.structurallyValid(structuralDurationMs, "audio/m4a",
                        "AAC_DECODER_UNSUPPORTED");
            }
            var track = tracks.getFirst();
            long declaredFrames = track.getMeta().getTotalFrames();
            if (declaredFrames <= 0 || declaredFrames > MAX_DECODED_FRAMES) {
                return Verification.invalid("AAC_DECODE_LIMIT_EXCEEDED");
            }
            ByteBuffer codecPrivate = track.getMeta().getCodecPrivate();
            if (codecPrivate == null || !codecPrivate.hasRemaining() || codecPrivate.remaining() > 1_024) {
                return Verification.structurallyValid(structuralDurationMs, "audio/m4a",
                        "AAC_DECODER_UNSUPPORTED");
            }
            AACDecoder decoder;
            try {
                decoder = new AACDecoder(codecPrivate.duplicate());
            } catch (AACException | RuntimeException error) {
                return Verification.structurallyValid(structuralDurationMs, "audio/m4a",
                        "AAC_DECODER_UNSUPPORTED");
            }
            DecodeAccumulator decoded = new DecodeAccumulator();
            ByteBuffer pcmScratch = ByteBuffer.allocate(MAX_PCM_BYTES_PER_FRAME);
            while (true) {
                Packet packet;
                try {
                    packet = track.nextFrame();
                } catch (IOException | RuntimeException error) {
                    return decoded.frameCount() == 0
                            ? Verification.structurallyValid(structuralDurationMs, "audio/m4a",
                            "AAC_DECODER_UNSUPPORTED")
                            : Verification.invalid("AAC_ISO_BMFF_SAMPLE_MAPPING_INVALID");
                }
                if (packet == null) break;
                if (packet.getData() == null || !packet.getData().hasRemaining()) {
                    return Verification.invalid("AAC_FRAME_EMPTY");
                }
                try {
                    pcmScratch.clear();
                    if (!decoded.accept(decoder.decodeFrame(packet.getData().duplicate(), pcmScratch))) {
                        return Verification.invalid("AAC_DECODE_OUTPUT_INVALID");
                    }
                } catch (IOException | RuntimeException error) {
                    return Verification.invalid("AAC_FRAME_DECODE_FAILED");
                }
            }
            if (decoded.frameCount() != declaredFrames) {
                return Verification.invalid("AAC_DECODE_FRAME_COUNT_MISMATCH");
            }
            if (!durationMatches(structuralDurationMs, decoded.durationMs())) {
                return Verification.invalid("AAC_DECODE_DURATION_MISMATCH");
            }
            return Verification.verified(decoded.durationMs(), "audio/m4a", decoded.contentHash());
        } catch (IOException error) {
            return Verification.structurallyValid(structuralDurationMs, "audio/m4a",
                    "AAC_DECODER_UNSUPPORTED");
        }
    }

    private static Long findAacAudioDuration(byte[] bytes, Box moov, long mediaDataBytes) {
        int cursor = moov.contentStart();
        while (cursor < moov.end()) {
            Box child = readBox(bytes, cursor, moov.end());
            if (child == null) return null;
            if ("trak".equals(child.type())) {
                Long duration = aacTrackDuration(bytes, child, mediaDataBytes);
                if (duration != null) return duration;
            }
            cursor = child.end();
        }
        return null;
    }

    private static Long aacTrackDuration(byte[] bytes, Box trak, long mediaDataBytes) {
        Box mdia = childBox(bytes, trak, "mdia");
        if (mdia == null) return null;
        Box handler = childBox(bytes, mdia, "hdlr");
        if (handler == null || handler.end() - handler.contentStart() < 12
                || !"soun".equals(ascii(bytes, handler.contentStart() + 8))) {
            return null;
        }
        Box minf = childBox(bytes, mdia, "minf");
        Box stbl = minf == null ? null : childBox(bytes, minf, "stbl");
        Box stsd = stbl == null ? null : childBox(bytes, stbl, "stsd");
        if (stsd == null || !containsMp4aSampleEntry(bytes, stsd)) return null;
        Box mdhd = childBox(bytes, mdia, "mdhd");
        MediaClock mediaClock = mdhd == null ? null : mediaClock(bytes, mdhd);
        SampleTiming timing = stbl == null ? null : sampleTiming(bytes, childBox(bytes, stbl, "stts"));
        SampleSizes sizes = stbl == null ? null : sampleSizes(bytes, childBox(bytes, stbl, "stsz"));
        if (mediaClock == null || timing == null || sizes == null
                || timing.sampleCount() != sizes.sampleCount()
                || timing.sampleCount() > MAX_DECODED_FRAMES
                || sizes.totalBytes() <= 0 || sizes.totalBytes() > mediaDataBytes) {
            return null;
        }
        long durationDifference = mediaClock.durationUnits() >= timing.durationUnits()
                ? mediaClock.durationUnits() - timing.durationUnits()
                : timing.durationUnits() - mediaClock.durationUnits();
        long durationTolerance = Math.max(timing.maximumSampleDelta(), mediaClock.timescale() / 100L);
        if (durationDifference > durationTolerance) return null;
        double durationMs = timing.durationUnits() * 1_000d / mediaClock.timescale();
        if (!Double.isFinite(durationMs) || durationMs <= 0 || durationMs > Long.MAX_VALUE) return null;
        return Math.max(1L, Math.round(durationMs));
    }

    private static boolean containsMp4aSampleEntry(byte[] bytes, Box stsd) {
        int payloadSize = stsd.end() - stsd.contentStart();
        if (payloadSize < 8) return false;
        long entryCount = unsignedInt(bytes, stsd.contentStart() + 4);
        int cursor = stsd.contentStart() + 8;
        for (long index = 0; index < entryCount && cursor < stsd.end(); index++) {
            Box entry = readBox(bytes, cursor, stsd.end());
            if (entry == null) return false;
            if ("mp4a".equals(entry.type())) return true;
            cursor = entry.end();
        }
        return false;
    }

    private static MediaClock mediaClock(byte[] bytes, Box mdhd) {
        int start = mdhd.contentStart();
        if (mdhd.end() - start < 20) return null;
        int version = unsigned(bytes[start]);
        long timescale;
        long duration;
        if (version == 0) {
            timescale = unsignedInt(bytes, start + 12);
            duration = unsignedInt(bytes, start + 16);
        } else if (version == 1 && mdhd.end() - start >= 32) {
            timescale = unsignedInt(bytes, start + 20);
            duration = unsignedLong(bytes, start + 24);
        } else {
            return null;
        }
        if (timescale <= 0 || duration <= 0) return null;
        return new MediaClock(timescale, duration);
    }

    private static SampleTiming sampleTiming(byte[] bytes, Box stts) {
        if (stts == null || stts.end() - stts.contentStart() < 8) return null;
        int start = stts.contentStart();
        long entryCount = unsignedInt(bytes, start + 4);
        if (entryCount > (stts.end() - (long) start - 8L) / 8L) return null;
        int cursor = start + 8;
        long sampleCount = 0;
        long durationUnits = 0;
        long maximumSampleDelta = 0;
        for (long index = 0; index < entryCount; index++) {
            long count = unsignedInt(bytes, cursor);
            long delta = unsignedInt(bytes, cursor + 4);
            if (count <= 0 || delta <= 0 || count > Long.MAX_VALUE / delta) return null;
            long entryDuration = count * delta;
            if (sampleCount > Long.MAX_VALUE - count
                    || durationUnits > Long.MAX_VALUE - entryDuration) return null;
            sampleCount += count;
            durationUnits += entryDuration;
            maximumSampleDelta = Math.max(maximumSampleDelta, delta);
            cursor += 8;
        }
        return sampleCount == 0 ? null : new SampleTiming(sampleCount, durationUnits, maximumSampleDelta);
    }

    private static SampleSizes sampleSizes(byte[] bytes, Box stsz) {
        if (stsz == null || stsz.end() - stsz.contentStart() < 12) return null;
        int start = stsz.contentStart();
        long fixedSampleSize = unsignedInt(bytes, start + 4);
        long sampleCount = unsignedInt(bytes, start + 8);
        if (sampleCount <= 0) return null;
        if (fixedSampleSize > 0) {
            if (sampleCount > Long.MAX_VALUE / fixedSampleSize) return null;
            return new SampleSizes(sampleCount, sampleCount * fixedSampleSize);
        }
        if (sampleCount > (stsz.end() - (long) start - 12L) / 4L) return null;
        int cursor = start + 12;
        long totalBytes = 0;
        for (long index = 0; index < sampleCount; index++) {
            long sampleSize = unsignedInt(bytes, cursor);
            if (sampleSize <= 0 || totalBytes > Long.MAX_VALUE - sampleSize) return null;
            totalBytes += sampleSize;
            cursor += 4;
        }
        return new SampleSizes(sampleCount, totalBytes);
    }

    private static Box childBox(byte[] bytes, Box parent, String type) {
        int cursor = parent.contentStart();
        while (cursor < parent.end()) {
            Box child = readBox(bytes, cursor, parent.end());
            if (child == null) return null;
            if (type.equals(child.type())) return child;
            cursor = child.end();
        }
        return null;
    }

    private static boolean hasSupportedBrand(byte[] bytes, Box ftyp) {
        if (ftyp.end() - ftyp.contentStart() < 8) return false;
        if (ISO_BMFF_AUDIO_BRANDS.contains(ascii(bytes, ftyp.contentStart()))) return true;
        for (int offset = ftyp.contentStart() + 8; offset + 4 <= ftyp.end(); offset += 4) {
            if (ISO_BMFF_AUDIO_BRANDS.contains(ascii(bytes, offset))) return true;
        }
        return false;
    }

    private static int adtsOffset(byte[] bytes) {
        if (isAdtsSync(bytes, 0)) return 0;
        if (bytes.length < 10 || bytes[0] != 'I' || bytes[1] != 'D' || bytes[2] != '3') return -1;
        for (int index = 6; index <= 9; index++) {
            if ((unsigned(bytes[index]) & 0x80) != 0) return -1;
        }
        int tagSize = (unsigned(bytes[6]) << 21) | (unsigned(bytes[7]) << 14)
                | (unsigned(bytes[8]) << 7) | unsigned(bytes[9]);
        int footerSize = unsigned(bytes[3]) == 4 && (unsigned(bytes[5]) & 0x10) != 0 ? 10 : 0;
        long offset = 10L + tagSize + footerSize;
        return offset <= Integer.MAX_VALUE && offset < bytes.length && isAdtsSync(bytes, (int) offset)
                ? (int) offset : -1;
    }

    private static boolean looksLikeIsoBmff(byte[] bytes) {
        return bytes.length >= 12 && "ftyp".equals(ascii(bytes, 4));
    }

    private static Box readBox(byte[] bytes, int offset, int limit) {
        if (offset < 0 || limit > bytes.length || limit - offset < 8) return null;
        long size = unsignedInt(bytes, offset);
        int headerSize = 8;
        if (size == 1) {
            if (limit - offset < 16) return null;
            size = unsignedLong(bytes, offset + 8);
            headerSize = 16;
        } else if (size == 0) {
            size = limit - offset;
        }
        if (size < headerSize || size > limit - (long) offset || size > Integer.MAX_VALUE) return null;
        int end = offset + (int) size;
        return new Box(offset + headerSize, end, ascii(bytes, offset + 4));
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return ((long) unsigned(bytes[offset]) << 24)
                | ((long) unsigned(bytes[offset + 1]) << 16)
                | ((long) unsigned(bytes[offset + 2]) << 8)
                | unsigned(bytes[offset + 3]);
    }

    private static long unsignedLong(byte[] bytes, int offset) {
        long high = unsignedInt(bytes, offset);
        long low = unsignedInt(bytes, offset + 4);
        if ((high & 0x8000_0000L) != 0) return -1;
        return (high << 32) | low;
    }

    private static String ascii(byte[] bytes, int offset) {
        return new String(bytes, offset, 4, StandardCharsets.ISO_8859_1);
    }

    private static boolean containsNonZero(byte[] bytes, int from, int to) {
        if (from < 0 || to > bytes.length || from >= to) return false;
        for (int index = from; index < to; index++) {
            if (bytes[index] != 0) return true;
        }
        return false;
    }

    private static boolean isAdtsSync(byte[] bytes, int offset) {
        return offset >= 0 && bytes.length - offset >= 2
                && unsigned(bytes[offset]) == 0xff
                && (unsigned(bytes[offset + 1]) & 0xf6) == 0xf0;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static long durationMs(long sampleFrames, int sampleRate) {
        if (sampleFrames <= 0 || sampleRate <= 0) return 0L;
        return Math.max(1L, Math.round(sampleFrames * 1_000d / sampleRate));
    }

    private static boolean durationMatches(long structuralDurationMs, long decodedDurationMs) {
        return Math.abs(structuralDurationMs - decodedDurationMs) <= 2L;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256不可用", error);
        }
    }

    private record Box(int contentStart, int end, String type) {
    }

    private record MediaClock(long timescale, long durationUnits) {
    }

    private record SampleTiming(long sampleCount, long durationUnits, long maximumSampleDelta) {
    }

    private record SampleSizes(long sampleCount, long totalBytes) {
    }

    private static final class DecodeAccumulator {

        private AudioFormat format;
        private int frameCount;
        private long sampleFrames;
        private final MessageDigest contentDigest = sha256();

        boolean accept(AudioBuffer audio) {
            if (audio == null || audio.getData() == null) return false;
            int pcmBytes = audio.getData().remaining();
            AudioFormat current = audio.getFormat();
            if (pcmBytes <= 0 || pcmBytes > MAX_PCM_BYTES_PER_FRAME || current == null
                    || current.getSampleRate() < 7_350 || current.getSampleRate() > 96_000
                    || current.getChannels() <= 0 || current.getChannels() > 8
                    || current.getSampleSizeInBits() <= 0 || current.getSampleSizeInBits() > 32
                    || current.getFrameSize() <= 0) {
                return false;
            }
            if (format == null) {
                format = current;
                contentDigest.update(ByteBuffer.allocate(20)
                        .putInt(current.getSampleRate())
                        .putInt(current.getChannels())
                        .putInt(current.getSampleSizeInBits())
                        .putInt(current.isSigned() ? 1 : 0)
                        .putInt(current.isBigEndian() ? 1 : 0)
                        .array());
            } else if (format.getSampleRate() != current.getSampleRate()
                    || format.getChannels() != current.getChannels()
                    || format.getSampleSizeInBits() != current.getSampleSizeInBits()
                    || format.isSigned() != current.isSigned()
                    || format.isBigEndian() != current.isBigEndian()) {
                return false;
            }
            int pcmFrames = current.bytesToFrames(pcmBytes);
            if (pcmFrames <= 0 || current.framesToBytes(pcmFrames) != pcmBytes
                    || (audio.getNFrames() > 0 && audio.getNFrames() != pcmFrames)
                    || frameCount >= MAX_DECODED_FRAMES) {
                return false;
            }
            try {
                sampleFrames = Math.addExact(sampleFrames, pcmFrames);
            } catch (ArithmeticException error) {
                return false;
            }
            if (AacAdtsRecordingMediaVerifier.durationMs(sampleFrames, current.getSampleRate())
                    > MAX_DECODED_DURATION_MS) return false;
            contentDigest.update(audio.getData().duplicate());
            frameCount++;
            return true;
        }

        int frameCount() {
            return frameCount;
        }

        long durationMs() {
            return AacAdtsRecordingMediaVerifier.durationMs(sampleFrames, format.getSampleRate());
        }

        String contentHash() {
            if (frameCount == 0) throw new IllegalStateException("未解码音频无内容指纹");
            return "pcm-sha256-v1:" + HexFormat.of().formatHex(contentDigest.digest());
        }
    }
}
