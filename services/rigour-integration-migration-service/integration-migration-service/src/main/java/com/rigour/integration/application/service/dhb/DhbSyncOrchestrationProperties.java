package com.rigour.integration.application.service.dhb;

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
    private static final ZoneId DEFAULT_WINDOW_ZONE = ZoneId.of("Asia/Shanghai");

    private boolean enabled = false;
    private String cron = DEFAULT_CRON;
    private int maxPages = 100;
    private String scheduledWindowFrom;

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
        scheduledWindowFromInstant();
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
        throw new IllegalStateException("订货宝统一同步scheduled-window-from必须是"
                + "yyyy-MM-dd、yyyy-MM-ddTHH:mm:ss或带时区的ISO-8601时间");
    }

    @FunctionalInterface
    private interface Parser {
        Instant parse(String value);
    }
}
