package com.rigour.sales.temporarycheckin;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Shared streaming response, called only after the controller's owner/admin authorization. */
final class TemporaryCheckinMediaResponses {
    private TemporaryCheckinMediaResponses() {}

    static ResponseEntity<?> respond(TemporaryCheckinService.AdminMedia content,
            String rangeHeader, boolean download) {
        ContentDisposition disposition = ContentDisposition.builder(download ? "attachment" : "inline")
                .filename(content.originalFilename(), StandardCharsets.UTF_8).build();
        if (rangeHeader != null && !rangeHeader.isBlank()) return rangedResponse(content, rangeHeader, disposition);
        ResponseEntity.BodyBuilder response = headers(ResponseEntity.ok(), content, disposition);
        if (content.sizeBytes() > 0) response.contentLength(content.sizeBytes());
        return response.body(new InputStreamResource(content.open()));
    }

    private static ResponseEntity.BodyBuilder headers(ResponseEntity.BodyBuilder response,
            TemporaryCheckinService.AdminMedia content, ContentDisposition disposition) {
        return response.contentType(safeMediaType(content.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
    }

    private static ResponseEntity<?> rangedResponse(TemporaryCheckinService.AdminMedia content,
            String rangeHeader, ContentDisposition disposition) {
        long size = content.sizeBytes();
        if (size <= 0) return rangeNotSatisfiable(size);
        try {
            List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
            if (ranges.size() != 1) return rangeNotSatisfiable(size);
            long start = ranges.getFirst().getRangeStart(size);
            long end = ranges.getFirst().getRangeEnd(size);
            if (start < 0 || end < start || end >= size) return rangeNotSatisfiable(size);
            InputStream input = content.open();
            try {
                input.skipNBytes(start);
            } catch (IOException exception) {
                try { input.close(); } catch (IOException ignored) { /* Retain the range failure. */ }
                return rangeNotSatisfiable(size);
            }
            long length = end - start + 1;
            return headers(ResponseEntity.status(HttpStatus.PARTIAL_CONTENT), content, disposition)
                    .contentLength(length)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size)
                    .body(new InputStreamResource(new LimitedInputStream(input, length)));
        } catch (IllegalArgumentException exception) {
            return rangeNotSatisfiable(size);
        }
    }

    private static ResponseEntity<Void> rangeNotSatisfiable(long size) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + Math.max(size, 0)).build();
    }

    private static MediaType safeMediaType(String value) {
        try { return MediaType.parseMediaType(value); }
        catch (IllegalArgumentException exception) { return MediaType.APPLICATION_OCTET_STREAM; }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        private LimitedInputStream(InputStream input, long remaining) { super(input); this.remaining = remaining; }
        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = super.read();
            if (value >= 0) remaining--;
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining <= 0) return -1;
            int count = super.read(bytes, offset, (int) Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
        @Override public long skip(long count) throws IOException {
            long skipped = super.skip(Math.min(count, remaining));
            remaining -= skipped;
            return skipped;
        }
    }
}
