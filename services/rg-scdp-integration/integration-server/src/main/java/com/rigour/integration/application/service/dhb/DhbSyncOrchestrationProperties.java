package com.rigour.integration.application.service.dhb;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 订货宝统一同步编排配置。 */
@ConfigurationProperties(prefix = "rigour.integration.dhb.orchestration")
public class DhbSyncOrchestrationProperties {
    public static final String DEFAULT_CRON = "0 5/30 * * * ?";
    /**
     * 订单页「同步订单」按钮首次增量同步的默认起点，与 Order 领域
     * {@code HistorySyncRules.CUTOVER}（{@code 2026-09-04T00:00:00+08:00}）保持一致：
     * 9/4 之后订货宝订单才允许新建，9/4 之前以飞书历史订单为主体、订货宝只回款。
     * 需要提前回补时用 {@code incremental-window-from} 覆盖。
     */
    public static final String DEFAULT_ORDER_INCREMENTAL_WINDOW_FROM = "2026-09-04T00:00:00+08:00";
    /** 单次来源拉取的最大时间跨度；积压更长时由调用方按片推进。 */
    public static final Duration DEFAULT_INCREMENTAL_WINDOW = Duration.ofDays(7);
    private static final Duration MIN_INCREMENTAL_WINDOW = Duration.ofHours(1);
    private static final Duration MAX_INCREMENTAL_WINDOW = Duration.ofDays(90);
    private static final ZoneId DEFAULT_WINDOW_ZONE = ZoneId.of("Asia/Shanghai");

    private boolean enabled = false;
    private String cron = DEFAULT_CRON;
    private int maxPages = 100;
    private String scheduledWindowFrom;
    private String incrementalWindowFrom;
    private Duration incrementalWindow = DEFAULT_INCREMENTAL_WINDOW;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int maxPages) { this.maxPages = maxPages; }
    public String getScheduledWindowFrom() { return scheduledWindowFrom; }
    public void setScheduledWindowFrom(String scheduledWindowFrom) {
        this.scheduledWindowFrom = scheduledWindowFrom;
    }

    public Instant scheduledWindowFromInstant() {
        if (scheduledWindowFrom == null || scheduledWindowFrom.isBlank()) return null;
        return parseWindowStart(scheduledWindowFrom.strip());
    }

    public String getIncrementalWindowFrom() { return incrementalWindowFrom; }
    public void setIncrementalWindowFrom(String incrementalWindowFrom) {
        this.incrementalWindowFrom = incrementalWindowFrom;
    }

    /** 订单页按钮增量包的首次同步起点；未配置时使用与订单业务分界一致的默认值。 */
    public Instant incrementalWindowFromInstant() {
        if (incrementalWindowFrom == null || incrementalWindowFrom.isBlank())
            return parseWindowStart(DEFAULT_ORDER_INCREMENTAL_WINDOW_FROM);
        return parseWindowStart(incrementalWindowFrom.strip());
    }

    public Duration getIncrementalWindow() { return incrementalWindow; }
    public void setIncrementalWindow(Duration incrementalWindow) {
        this.incrementalWindow = incrementalWindow;
    }

    /**
     * 单次来源拉取的实际切片跨度：配置缺失、为零或为负时退回默认 7 天，超过上限时收到 90 天，
     * 保证订单页按钮增量即使遇到错误配置也不会退化成零跨度窗口或一次拉取过长积压。
     */
    public Duration effectiveIncrementalWindow() {
        if (incrementalWindow == null || incrementalWindow.isZero() || incrementalWindow.isNegative()) {
            return DEFAULT_INCREMENTAL_WINDOW;
        }
        return incrementalWindow.compareTo(MAX_INCREMENTAL_WINDOW) > 0
                ? MAX_INCREMENTAL_WINDOW : incrementalWindow;
    }

    public void validate() {
        if (maxPages < 1 || maxPages > 100) {
            throw new IllegalStateException("订货宝统一同步max-pages必须在1到100之间");
        }
        if (cron == null || cron.isBlank()) {
            throw new IllegalStateException("订货宝统一同步cron不能为空");
        }
        if (enabled && (scheduledWindowFrom == null || scheduledWindowFrom.isBlank())) {
            throw new IllegalStateException("订货宝统一同步scheduled-window-from不能为空");
        }
        if (incrementalWindow == null || incrementalWindow.compareTo(MIN_INCREMENTAL_WINDOW) < 0
                || incrementalWindow.compareTo(MAX_INCREMENTAL_WINDOW) > 0) {
            throw new IllegalStateException("订货宝订单增量窗口单次跨度必须在1小时到90天之间");
        }
        scheduledWindowFromInstant();
        incrementalWindowFromInstant();
    }

    static Instant parseWindowStart(String value) {
        List<Parser> parsers = List.of(
                text -> OffsetDateTime.parse(text).toInstant(),
                text -> LocalDateTime.parse(text).atZone(DEFAULT_WINDOW_ZONE).toInstant(),
                text -> LocalDate.parse(text).atStartOfDay(DEFAULT_WINDOW_ZONE).toInstant());
        for (Parser parser : parsers) {
            try {
                return parser.parse(value);
            } catch (DateTimeParseException ignored) {
                // Try the next supported business-friendly date format.
            }
        }
        throw new IllegalStateException("订货宝统一同步起始时间(scheduled-window-from/incremental-window-from)必须是"
                + "yyyy-MM-dd、yyyy-MM-ddTHH:mm:ss或带时区的ISO-8601时间");
    }

    @FunctionalInterface
    private interface Parser {
        Instant parse(String value);
    }
}
