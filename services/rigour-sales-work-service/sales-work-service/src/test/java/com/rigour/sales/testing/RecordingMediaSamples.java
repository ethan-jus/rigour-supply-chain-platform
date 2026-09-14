package com.rigour.sales.testing;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 真实 AAC-LC 固定夹具，测试运行时不依赖 ffmpeg。
 * ADTS 由 ffmpeg 8.0 编码 50ms 正弦波；对应 M4A 使用 ffmpeg 9.0.1 执行
 * {@code ffmpeg -i sample.aac -c:a copy -movflags +faststart sample.m4a} 完整重封装。
 * alternate M4A 由 ffmpeg 9.0.1 用 880Hz/44.1kHz/50ms/AAC-LC/64kbps 参数生成。
 */
public final class RecordingMediaSamples {

    public static final int ADTS_SIZE = 611;
    public static final String ADTS_SHA256 =
            "7de534c59c191dcfe974b364e7159cdbf9466dc3efc3710f74c1643db2a508de";
    public static final int M4A_SIZE = 1_394;
    public static final String M4A_SHA256 =
            "8441b7334f5e2b63a9756c5cfde441cafa90c6aede88133e6cb817520541b458";
    public static final int ALTERNATE_M4A_SIZE = 1_587;
    public static final String ALTERNATE_M4A_SHA256 =
            "339dabb36d756714571964d48a9e619329aeaaeb3867a3773d675080d94f3124";

    private static final String ADTS = "//FQQBu//NwATGF2YzYzLjEuMTAxAAJEqlsojFSeifnxxz9+6qvXt9SXunF5KmskJEhDzDY2xUyUYU8lGFg0FTLKZZTMK05V85fefN8SRHk4/kzbLa9Ya1Iv0i/NX6Njo1tJjJjJM8zs7OztJNJNJNhNhNJMDAwMDAwMDAwMDIkUps2bllBgYGBgYGlllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllVLLLLLLLLLLLLLLLLz/8VBAGv/8AQqY2spd1lIPFlOxNosj0v/7H+/t1p76u9W//sf+3nrXHE4vX9v/7H/XrzrrWtXP7//3P+vt1rrjV6sZi/A+EcmOHFzd5LEv/qhw+t7tcrdMlbrATdY/lh1zP5fDXM/l/LXrP5fylrmH8vh/LXrmP5Rw65h8Ph8JeOcpGM+eJ2z69iNpCr1e56nY2VSsyyg0rm1AzyqBjYMSlUDSgwNKpwYGRAwMblxcGBgYGHUFKcGoEgYGigPbq0tN9WmI9FMgnnnnVPPsnHErwMDEgYGHcP/xUEAUf/wBFpmykjHizFRDA9D1/6f4+L11rWtT+v/8X8/qBIHe6Oaw2ymClDXQ9031WyFpjmc0xsbImx6thst/LYNln8vVsET+Xr+C7DHZ/L+XqCJL4erDbSV0jx4omaaRROI0qGri/qYIRBbPQtmg/YJ2f/og04+g7oS3OYITLjUZtJ3ED//r+Kd4Efxvch26x8Pi8ggebFlA9HTmAfDBhwf/8VBAAZ/8ARiBtHA=";

    private static final String M4A = "AAAAHGZ0eXBNNEEgAAACAE00QSBpc29taXNvMgAAAv9tb292AAAAbG12aGQAAAAAAAAAAAAAAAABrqoAACgAAAABAAABAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAACKnRyYWsAAABcdGtoZAAAAAMAAAAAAAAAAAAAAAEAAAAAACgAAAAAAAAAAAAAAAAAAQEAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAACRlZHRzAAAAHGVsc3QAAAAAAAAAAQAoAAAAAAAAAAEAAAAAAaJtZGlhAAAAIG1kaGQAAAAAAAAAAAAAAAAAAKxEAAAQAFXEAAAAAAAtaGRscgAAAAAAAAAAc291bgAAAAAAAAAAAAAAAFNvdW5kSGFuZGxlcgAAAAFNbWluZgAAABBzbWhkAAAAAAAAAAAAAAAkZGluZgAAABxkcmVmAAAAAAAAAAEAAAAMdXJsIAAAAAEAAAERc3RibAAAAGdzdHNkAAAAAAAAAAEAAABXbXA0YQAAAAAAAAABAAAAAAAAAAAAAQAQAAAAAKxEAAAAAAAzZXNkcwAAAAADgICAIgABAASAgIAUQBUAAAAAAM2UAADEJwWAgIACEggGgICAAQIAAAAYc3R0cwAAAAAAAAABAAAABAAABAAAAAAcc3RzYwAAAAAAAAABAAAAAQAAAAQAAAABAAAAJHN0c3oAAAAAAAAAAAAAAAQAAADWAAAA0AAAAJwAAAAFAAAAFHN0Y28AAAAAAAAAAQAAAysAAAAac2dwZAEAAAByb2xsAAAAAgAAAAH//wAAABxzYmdwAAAAAHJvbGwAAAABAAAABAAAAAEAAABhdWR0YQAAAFltZXRhAAAAAAAAACFoZGxyAAAAAAAAAABtZGlyYXBwbAAAAAAAAAAAAAAAACxpbHN0AAAAJKl0b28AAAAcZGF0YQAAAAEAAAAATGF2ZjYzLjEuMTAxAAAACGZyZWUAAAJPbWRhdNwATGF2YzYzLjEuMTAxAAJEqlsojFSeifnxxz9+6qvXt9SXunF5KmskJEhDzDY2xUyUYU8lGFg0FTLKZZTMK05V85fefN8SRHk4/kzbLa9Ya1Iv0i/NX6Njo1tJjJjJM8zs7OztJNJNJNhNhNJMDAwMDAwMDAwMDIkUps2bllBgYGBgYGlllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllVLLLLLLLLLLLLLLLLwBCpjayl3WUg8WU7E2iyPS//sf7+3Wnvq71b/+x/7eetccTi9f2//sf9evOuta1c/v//c/6+3WuuNXqxmL8D4RyY4cXN3ksS/+qHD63u1yt0yVusBN1j+WHXM/l8Ncz+X8tes/l/KWuYfy+H8teuY/lHDrmHw+Hwl45ykYz54nbPr2I2kKvV7nqdjZVKzLKDSubUDPKoGNgxKVQNKDA0qnBgZEDAxuXFwYGBgYdQUpwagSBgaKA9urS031aYj0UyCeeedU8+yccSvAwMSBgYdwARaZspIx4sxUQwPQ9f+n+Pi9da1rU/r//F/P6gSB3ujmsNspgpQ10PdN9VshaY5nNMbGyJserYbLfy2DZZ/L1bBE/l6/guwx2fy/l6giS+Hqw20ldI8eKJmmkUTiNKhq4v6mCEQWz0LZoP2Cdn/6INOPoO6EtzmCEy41GbSdxA//6/ineBH8b3IdusfD4vIIHmxZQPR05gHwwYcHARiBtHA=";

    private static final String ALTERNATE_M4A = "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";

    private RecordingMediaSamples() {
    }

    public static byte[] adts() {
        return Base64.getDecoder().decode(ADTS);
    }

    public static byte[] m4a() {
        return Base64.getDecoder().decode(M4A);
    }

    public static byte[] alternateM4a() {
        return Base64.getDecoder().decode(ALTERNATE_M4A);
    }

    /** Repeats the pinned four-frame ADTS sequence without synthesizing new media. */
    public static byte[] repeatAdts(int repetitions) {
        if (repetitions <= 0) throw new IllegalArgumentException("repetitions必须大于0");
        byte[] source = adts();
        byte[] result = new byte[Math.multiplyExact(source.length, repetitions)];
        for (int index = 0; index < repetitions; index++) {
            System.arraycopy(source, 0, result, index * source.length, source.length);
        }
        return result;
    }

    /** Expands the pinned M4A sample tables and repeats its genuine AAC packets in one chunk. */
    public static byte[] repeatM4a(int repetitions) {
        return repeatM4a(m4a(), repetitions);
    }

    public static byte[] repeatAlternateM4a(int repetitions) {
        return repeatM4a(alternateM4a(), repetitions);
    }

    private static byte[] repeatM4a(byte[] source, int repetitions) {
        if (repetitions <= 0) throw new IllegalArgumentException("repetitions必须大于0");
        if (repetitions == 1) return source;

        int stszType = boxTypeOffset(source, "stsz");
        int stszSize = intAt(source, stszType - 4);
        int stszEnd = stszType - 4 + stszSize;
        int fixedSampleSize = intAt(source, stszType + 8);
        int sampleCount = intAt(source, stszType + 12);
        if (fixedSampleSize != 0 || sampleCount <= 0
                || stszEnd != stszType + 16 + sampleCount * 4) {
            throw new IllegalArgumentException("夹具stsz格式不支持重复");
        }
        int sampleTableBytes = Math.multiplyExact(sampleCount, 4);
        int repeatedSampleCount = Math.multiplyExact(sampleCount, repetitions);

        int stcoType = boxTypeOffset(source, "stco");
        if (intAt(source, stcoType + 8) != 1) {
            throw new IllegalArgumentException("夹具必须只有一个chunk");
        }
        int originalChunkOffset = intAt(source, stcoType + 12);
        int mdatType = boxTypeOffset(source, "mdat");
        int mdatStart = mdatType - 4;
        int mdatEnd = Math.addExact(mdatStart, intAt(source, mdatStart));
        int mdatContentStart = mdatType + 4;
        if (originalChunkOffset != mdatContentStart || mdatEnd > source.length) {
            throw new IllegalArgumentException("夹具mdat/stco不一致");
        }
        int mediaBytes = mdatEnd - mdatContentStart;
        int declaredMediaBytes = 0;
        for (int index = 0; index < sampleCount; index++) {
            declaredMediaBytes = Math.addExact(
                    declaredMediaBytes, intAt(source, stszType + 16 + index * 4));
        }
        if (declaredMediaBytes != mediaBytes) {
            throw new IllegalArgumentException("夹具sample大小与mdat不一致");
        }

        int tableGrowth = Math.multiplyExact(sampleTableBytes, repetitions - 1);
        int mediaGrowth = Math.multiplyExact(mediaBytes, repetitions - 1);
        byte[] result = new byte[Math.addExact(source.length, Math.addExact(tableGrowth, mediaGrowth))];
        int cursor = 0;
        cursor = copy(source, 0, stszEnd, result, cursor);
        for (int index = 1; index < repetitions; index++) {
            cursor = copy(source, stszType + 16, sampleTableBytes, result, cursor);
        }
        cursor = copy(source, stszEnd, mdatContentStart - stszEnd, result, cursor);
        for (int index = 0; index < repetitions; index++) {
            cursor = copy(source, mdatContentStart, mediaBytes, result, cursor);
        }
        cursor = copy(source, mdatEnd, source.length - mdatEnd, result, cursor);
        if (cursor != result.length) throw new IllegalStateException("重复M4A长度计算错误");

        for (String parent : new String[] {"stsz", "stbl", "minf", "mdia", "trak", "moov"}) {
            int type = boxTypeOffset(result, parent);
            putInt(result, type - 4, Math.addExact(intAt(result, type - 4), tableGrowth));
        }
        int resultStts = boxTypeOffset(result, "stts");
        if (intAt(result, resultStts + 8) != 1) {
            throw new IllegalArgumentException("夹具stts必须只有一个entry");
        }
        putInt(result, resultStts + 12, repeatedSampleCount);
        int resultStsc = boxTypeOffset(result, "stsc");
        if (intAt(result, resultStsc + 8) != 1) {
            throw new IllegalArgumentException("夹具stsc必须只有一个entry");
        }
        putInt(result, resultStsc + 16, repeatedSampleCount);
        int resultStsz = boxTypeOffset(result, "stsz");
        putInt(result, resultStsz + 12, repeatedSampleCount);
        int resultMdhd = boxTypeOffset(result, "mdhd");
        if (result[resultMdhd + 4] != 0) {
            throw new IllegalArgumentException("夹具mdhd必须是version 0");
        }
        putInt(result, resultMdhd + 20,
                Math.multiplyExact(intAt(source, boxTypeOffset(source, "mdhd") + 20), repetitions));
        int resultStco = boxTypeOffset(result, "stco");
        putInt(result, resultStco + 12, Math.addExact(originalChunkOffset, tableGrowth));
        int resultMdat = boxTypeOffset(result, "mdat");
        putInt(result, resultMdat - 4,
                Math.addExact(intAt(source, mdatStart), mediaGrowth));
        return result;
    }

    private static int copy(byte[] source, int sourceOffset, int length,
                            byte[] target, int targetOffset) {
        System.arraycopy(source, sourceOffset, target, targetOffset, length);
        return targetOffset + length;
    }

    private static int boxTypeOffset(byte[] bytes, String type) {
        byte[] expected = type.getBytes(StandardCharsets.ISO_8859_1);
        for (int offset = 4; offset <= bytes.length - expected.length; offset++) {
            boolean matches = true;
            for (int index = 0; index < expected.length; index++) {
                if (bytes[offset + index] != expected[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) return offset;
        }
        throw new IllegalArgumentException("夹具缺少 " + type + " box");
    }

    private static int intAt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).getInt();
    }

    private static void putInt(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes, offset, 4).putInt(value);
    }
}
