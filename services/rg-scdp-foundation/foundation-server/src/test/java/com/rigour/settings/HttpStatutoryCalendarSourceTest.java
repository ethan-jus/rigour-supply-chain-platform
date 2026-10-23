package com.rigour.settings;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.settings.domain.calendar.*;
import com.rigour.settings.infrastructure.integration.HttpStatutoryCalendarSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

class HttpStatutoryCalendarSourceTest {
    final RestClient client = mock(RestClient.class, RETURNS_DEEP_STUBS);
    final RestClient.Builder builder = mock(RestClient.Builder.class, RETURNS_SELF);
    final tools.jackson.databind.ObjectMapper json = JsonMapper.builder().build();
    static final String SEARCH = "https://sousuo.www.gov.cn/search-gov/data";

    HttpStatutoryCalendarSource source(String search, String article) {
        when(builder.clone()).thenReturn(builder);
        when(builder.build()).thenReturn(client);
        when(client.get().uri(any(URI.class)).retrieve().body(byte[].class))
                .thenReturn(
                        search.getBytes(StandardCharsets.UTF_8),
                        article.getBytes(StandardCharsets.UTF_8));
        return new HttpStatutoryCalendarSource(builder, json, SEARCH);
    }

    String fixture(String file) throws Exception {
        return new String(
                getClass().getResourceAsStream("/calendar/" + file).readAllBytes(),
                StandardCharsets.UTF_8);
    }

    @Test
    void officialAnnouncementsForMultipleYearsProduceTheVerifiedDatesAndArchiveOriginalText()
            throws Exception {
        for (int year : new int[] {2025, 2026}) {
            var data =
                    source(
                                    fixture("government-search-" + year + ".json"),
                                    fixture("government-notice-" + year + ".html"))
                            .fetch(year);
            var expected = json.readValue(fixture(year + ".json"), StatutorySourceYear.class);
            assertThat(
                            data.days().stream()
                                    .collect(
                                            Collectors.toMap(
                                                    StatutorySourceDay::date,
                                                    StatutorySourceDay::isOffDay)))
                    .isEqualTo(
                            expected.days().stream()
                                    .collect(
                                            Collectors.toMap(
                                                    StatutorySourceDay::date,
                                                    StatutorySourceDay::isOffDay)));
            assertThat(data.papers()).allMatch(url -> url.startsWith("https://www.gov.cn/"));
            assertThat(data.announcementText())
                    .contains(StatutoryNoticeParser.title(year), "国办发明电");
            reset(client);
        }
    }

    @Test
    void notPublishedDiffersFromSearchFailureOrUnknownResponse() throws Exception {
        assertThat(source(fixture("government-search-2027.json"), "unused").fetch(2027).days())
                .isEmpty();
        reset(client);
        assertThatThrownBy(() -> source("{\"code\":500}", "").fetch(2027))
                .hasMessageContaining("检索暂不可用");
    }

    @Test
    void untrustedSearchLinksAndWrongAnnualNoticeCannotBeUsed() throws Exception {
        String search = fixture("government-search-2026.json");
        assertThatThrownBy(
                        () ->
                                source(
                                                search.replace(
                                                        "https://www.gov.cn/",
                                                        "https://gov.cn.evil.example/"),
                                                "unused")
                                        .fetch(2026))
                .hasMessageContaining("仅允许政府");
        reset(client);
        assertThatThrownBy(() -> source(search, fixture("government-notice-2025.html")).fetch(2026))
                .hasMessageContaining("标题");
    }

    @Test
    void incompleteDatesCountsAndHolidayWorkdayConflictsFailClosed() throws Exception {
        String search = fixture("government-search-2026.json");
        String html = fixture("government-notice-2026.html");
        assertThatThrownBy(() -> source(search, html.replace("共9天", "共8天")).fetch(2026))
                .hasMessageContaining("天数");
        reset(client);
        assertThatThrownBy(() -> source(search, html.replace("七、国庆节：", "七、未知节日：")).fetch(2026))
                .hasMessageContaining("未识别");
        reset(client);
        assertThatThrownBy(() -> source(search, html.replace("2月14日（周六）", "2月15日（周六）")).fetch(2026))
                .hasMessageContaining("冲突");
    }

    @Test
    void newlyPublishedFutureNoticeAndItsCrossYearDatesNeedNoNewYearSpecificCode()
            throws Exception {
        var data =
                source(
                                fixture("government-search-2026.json"),
                                fixture("government-notice-2026.html"))
                        .fetch(2026);
        String simulated =
                data.announcementText()
                        .replace("关于2026年", "关于2027年")
                        .replace("1月1日（周四）至3日（周六）", "2026年12月31日至2027年1月2日");
        var future =
                StatutoryNoticeParser.parse(
                        2027, "https://www.gov.cn/test/simulated-notice", simulated);
        assertThat(future.days())
                .anySatisfy(
                        day -> {
                            assertThat(day.date()).isEqualTo(LocalDate.of(2026, 12, 31));
                            assertThat(day.isOffDay()).isTrue();
                        });
    }

    /** Explicit opt-in: performs only public, read-only government HTTP requests. */
    @Test
    @EnabledIfSystemProperty(named = "rigour.calendar.live-test", matches = "true")
    void readsTheGovernmentSearchAndAnnouncementsThroughTheRealHttpAdapter() {
        var source = new HttpStatutoryCalendarSource(RestClient.builder(), json, SEARCH);
        for (int year : new int[] {2025, 2026}) {
            var data = source.fetch(year);
            StatutoryCalendarValidation.validate(year, data);
            assertThat(data.days()).isNotEmpty();
            assertThat(data.announcementText()).contains(StatutoryNoticeParser.title(year));
        }
    }
}
