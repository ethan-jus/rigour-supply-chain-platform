package com.rigour.integration.application.port.out;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** 单次采集跨表共享的硬预算，使用单调时钟，不受系统时间调整影响。 */
public final class FeishuCaptureBudget {
    public static final int MAX_RECORDS = 20_000;
    public static final int MAX_PAGES = 200;
    public static final long MAX_RESPONSE_BYTES = 32L * 1024 * 1024;
    public static final Duration MAX_DURATION = Duration.ofSeconds(90);

    private final int maxRecords;
    private final int maxPages;
    private final long maxBytes;
    private final long durationNanos;
    private final LongSupplier nanoTime;
    private final long started;
    private int records;
    private int pages;
    private long bytes;

    public FeishuCaptureBudget() {
        this(MAX_RECORDS, MAX_PAGES, MAX_RESPONSE_BYTES, MAX_DURATION, System::nanoTime);
    }

    public FeishuCaptureBudget(int maxRecords, int maxPages, long maxBytes, Duration duration,
                               LongSupplier nanoTime) {
        if (maxRecords < 1 || maxRecords > MAX_RECORDS || maxPages < 1 || maxPages > MAX_PAGES
                || maxBytes < 1 || maxBytes > MAX_RESPONSE_BYTES || duration == null
                || duration.isZero() || duration.isNegative() || duration.compareTo(MAX_DURATION) > 0) {
            throw new IllegalArgumentException("飞书采集预算超出允许范围");
        }
        this.maxRecords = maxRecords;
        this.maxPages = maxPages;
        this.maxBytes = maxBytes;
        this.durationNanos = duration.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.started = nanoTime.getAsLong();
    }

    public void checkTime() { remainingTime(); }
    public Duration remainingTime() {
        long remaining = durationNanos - (nanoTime.getAsLong() - started);
        if (Thread.currentThread().isInterrupted() || remaining <= 0) {
            throw failure("TIME_LIMIT", "飞书采集已中断或超过时间限制，请缩小来源范围后重试");
        }
        return Duration.ofNanos(remaining);
    }

    public void beginPage() {
        checkTime();
        if (pages >= maxPages) throw failure("PAGE_LIMIT", "飞书采集超过分页上限");
        pages++;
    }

    public void addRecord() {
        checkTime();
        if (records >= maxRecords) throw failure("RECORD_LIMIT", "飞书采集超过记录上限");
        records++;
    }

    public void addBytes(int count) {
        checkTime();
        if (count < 0 || count > maxBytes - bytes) throw failure("SIZE_LIMIT", "飞书采集超过响应大小上限");
        bytes += count;
    }

    public int recordCount() { return records; }
    public int pageCount() { return pages; }
    public long responseBytes() { return bytes; }

    public static FeishuBitableClientException failure(String code, String message) {
        return new FeishuBitableClientException("FEISHU_CAPTURE_" + code, message, false);
    }
}
