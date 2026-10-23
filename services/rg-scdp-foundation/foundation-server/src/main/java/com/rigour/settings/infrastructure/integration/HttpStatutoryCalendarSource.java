package com.rigour.settings.infrastructure.integration;

import com.rigour.settings.application.port.out.StatutoryCalendarSource;
import com.rigour.settings.domain.calendar.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the public government policy search and announcement pages, not a dedicated holiday API.
 */
@Component
public class HttpStatutoryCalendarSource implements StatutoryCalendarSource {
    private static final Pattern ARTICLE =
            Pattern.compile(
                    "<div\\b[^>]*class=[\"'][^\"']*(?:trs_editor_view|TRS_Editor)[^\"']*[\"'][^>]*>(.*?)</div>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private final RestClient client;
    private final URI search;
    private final ObjectMapper json;

    public HttpStatutoryCalendarSource(
            RestClient.Builder builder,
            ObjectMapper json,
            @Value(
                            "${rigour.settings.calendar.government-search-url:https://sousuo.www.gov.cn/search-gov/data}")
                    String searchUrl) {
        this.json = json;
        this.search = official(searchUrl);
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(4))
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .build());
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.client = builder.clone().requestFactory(factory).build();
    }

    public StatutorySourceYear fetch(int year) {
        var uri =
                UriComponentsBuilder.fromUri(search)
                        .queryParam("t", "zhengcelibrary_gw")
                        .queryParam("q", StatutoryNoticeParser.title(year))
                        .queryParam("searchfield", "title")
                        .queryParam("p", 1)
                        .queryParam("n", 10)
                        .queryParam("sort", "score")
                        .queryParam("type", "gwyzcwjk")
                        .build()
                        .encode()
                        .toUri();
        var root = json.readTree(read(uri));
        if (root.path("code").asInt() == 1001
                && root.path("searchVO").isNull()
                && "抱歉，没有找到相关结果".equals(root.path("msg").asText())
                && root.path("data").isArray()
                && root.path("data").isEmpty())
            return new StatutorySourceYear(year, List.of(), List.of());
        var result = root.path("searchVO");
        if (root.path("code").asInt() != 200
                || !result.path("listVO").isArray()
                || !result.path("totalCount").isIntegralNumber())
            throw new IllegalArgumentException("政府公告检索暂不可用或返回结构发生变化");
        for (var row : result.path("listVO")) {
            if (!StatutoryNoticeParser.compact(text(row.path("title").asText()))
                    .equals(StatutoryNoticeParser.title(year))) continue;
            if (!"国务院办公厅".equals(row.path("puborg").asText()))
                throw new IllegalArgumentException("节假日公告发布机关不符");
            var article = official(row.path("url").asText().replaceFirst("^http://", "https://"));
            var content = ARTICLE.matcher(read(article));
            if (!content.find()) throw new IllegalArgumentException("政府公告正文结构发生变化，保留原日历");
            return StatutoryNoticeParser.parse(year, article.toString(), text(content.group(1)));
        }
        if (result.path("totalCount").asInt() > result.path("listVO").size())
            throw new IllegalArgumentException("政府公告检索结果不完整");
        return new StatutorySourceYear(year, List.of(), List.of());
    }

    private String read(URI uri) {
        byte[] bytes = client.get().uri(uri).retrieve().body(byte[].class);
        if (bytes == null || bytes.length == 0 || bytes.length > 600000)
            throw new IllegalArgumentException("政府公告响应无效");
        // Government HTML may declare UTF-8 only in meta tags, without an HTTP charset.
        String header =
                new String(bytes, 0, Math.min(bytes.length, 2048), StandardCharsets.ISO_8859_1);
        Charset encoding =
                Pattern.compile(
                                        "charset\\s*=\\s*[\"']?(?:gb2312|gbk|gb18030)",
                                        Pattern.CASE_INSENSITIVE)
                                .matcher(header)
                                .find()
                        ? Charset.forName("GB18030")
                        : StandardCharsets.UTF_8;
        String body = new String(bytes, encoding);
        if (body.isBlank()) throw new IllegalArgumentException("政府公告响应无效");
        return body;
    }

    private static URI official(String value) {
        var uri = URI.create(value);
        var host = uri.getHost();
        if (!"https".equals(uri.getScheme())
                || host == null
                || uri.getUserInfo() != null
                || uri.getPort() != -1 && uri.getPort() != 443
                || !(host.equals("gov.cn") || host.endsWith(".gov.cn")))
            throw new IllegalArgumentException("公告采集仅允许政府HTTPS站点");
        return uri;
    }

    static String text(String html) {
        return HtmlUtils.htmlUnescape(
                html.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1>", "")
                        .replaceAll("(?s)<[^>]+>", ""));
    }
}
