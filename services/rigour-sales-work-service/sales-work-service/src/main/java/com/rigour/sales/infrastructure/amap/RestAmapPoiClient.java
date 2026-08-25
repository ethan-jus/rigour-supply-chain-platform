package com.rigour.sales.infrastructure.amap;

import com.rigour.sales.application.port.out.AmapPoiClient;
import com.rigour.sales.application.port.out.AmapPoiException;
import com.rigour.sales.infrastructure.config.AmapProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 高德 Web 服务 place/around 适配器；key 只出现在请求参数，永不进入日志。 */
@Component
public class RestAmapPoiClient implements AmapPoiClient {

    private static final Logger log = LoggerFactory.getLogger(RestAmapPoiClient.class);
    // 现场拜访以球馆、运动场馆等体育休闲场所为主，同时保留餐饮、生活与住宿类门店。
    private static final String DEFAULT_TYPES = "080000|060000|070000|050000";

    private final RestClient restClient;
    private final AmapProperties properties;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<CacheKey, CacheEntry> nearbyCache = new ConcurrentHashMap<>();

    @Autowired
    public RestAmapPoiClient(RestClient.Builder builder, AmapProperties properties, ObjectMapper objectMapper) {
        this(createRestClient(builder, properties), properties, objectMapper);
    }

    RestAmapPoiClient(RestClient restClient, AmapProperties properties, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    private static RestClient createRestClient(RestClient.Builder builder, AmapProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(toMillis(properties.getConnectTimeout()));
        factory.setReadTimeout(toMillis(properties.getReadTimeout()));
        return builder.baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
    }

    @Override
    public NearbyPoiPage searchAround(String keyword, BigDecimal longitude, BigDecimal latitude,
                                      int radiusMeters, int page, int pageSize) {
        if (!StringUtils.hasText(properties.getWebKey())) {
            throw new AmapPoiException("高德 Web 服务 key 未配置");
        }
        CacheKey cacheKey = cacheKey(keyword, longitude, latitude, radiusMeters, page, pageSize);
        long now = System.nanoTime();
        CacheEntry cached = nearbyCache.get(cacheKey);
        if (cached != null && cached.expiresAtNanos() > now) {
            return cached.page();
        }
        if (cached != null) nearbyCache.remove(cacheKey, cached);
        long startedAt = System.nanoTime();
        try {
            String body = restClient.get().uri(uriBuilder -> {
                uriBuilder.path("/place/around")
                        .queryParam("key", properties.getWebKey())
                        .queryParam("location", longitude.toPlainString() + "," + latitude.toPlainString())
                        .queryParam("radius", radiusMeters)
                        .queryParam("offset", pageSize)
                        .queryParam("page", page)
                        .queryParam("extensions", "base")
                        .queryParam("sortrule", "distance");
                if (StringUtils.hasText(keyword)) {
                    uriBuilder.queryParam("keywords", keyword.trim());
                } else {
                    uriBuilder.queryParam("types", DEFAULT_TYPES);
                }
                return uriBuilder.build();
            }).retrieve().body(String.class);
            JsonNode response = objectMapper.readTree(body);
            if (response == null || !"1".equals(scalarText(response.get("status")))) {
                String info = response == null ? "empty" : scalarText(response.get("info"));
                log.warn("高德附近门店返回失败 endpoint=/place/around info={}", info);
                throw new AmapPoiException("高德附近门店返回失败");
            }
            List<NearbyPoi> pois = pois(response.get("pois"));
            long total = parseCount(scalarText(response.get("count")));
            log.info("高德附近门店查询成功 endpoint=/place/around radiusMeters={} items={} elapsedMs={}",
                    radiusMeters, pois.size(), (System.nanoTime() - startedAt) / 1_000_000);
            NearbyPoiPage result = new NearbyPoiPage(pois, page, pageSize, total);
            cache(cacheKey, result, now);
            return result;
        } catch (RestClientResponseException exception) {
            log.warn("高德附近门店HTTP失败 endpoint=/place/around httpStatus={}",
                    exception.getStatusCode().value());
            throw new AmapPoiException("高德附近门店HTTP失败");
        } catch (RestClientException exception) {
            log.warn("高德附近门店连接失败 endpoint=/place/around reason={}", exception.getClass().getSimpleName());
            throw new AmapPoiException("高德附近门店连接失败");
        } catch (AmapPoiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("高德附近门店响应解析失败 endpoint=/place/around reason={}", exception.getClass().getSimpleName());
            throw new AmapPoiException("高德附近门店响应解析失败");
        }
    }

    private static List<NearbyPoi> pois(JsonNode values) {
        if (values == null || !values.isArray()) {
            throw new IllegalArgumentException("pois must be an array");
        }
        List<NearbyPoi> result = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            NearbyPoi parsed = poi(value);
            if (parsed != null) result.add(parsed);
        }
        return List.copyOf(result);
    }

    private static NearbyPoi poi(JsonNode source) {
        if (source == null || !source.isObject()) return null;
        String id = scalarText(source.get("id"));
        String name = scalarText(source.get("name"));
        BigDecimal[] coordinates = coordinates(scalarText(source.get("location")));
        if (!StringUtils.hasText(id) || !StringUtils.hasText(name)
                || coordinates[0] == null || coordinates[1] == null) {
            return null;
        }
        return new NearbyPoi(id, name, scalarText(source.get("address")), scalarText(source.get("type")),
                scalarText(source.get("typecode")), coordinates[0], coordinates[1],
                distance(scalarText(source.get("distance"))));
    }

    /** 高德空字段有时会返回 []；对预期标量的非标量值按缺失处理。 */
    private static String scalarText(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        if (value.isTextual() || value.isNumber() || value.isBoolean()) {
            String text = value.asText();
            return StringUtils.hasText(text) ? text : null;
        }
        return null;
    }

    private static BigDecimal[] coordinates(String location) {
        if (!StringUtils.hasText(location)) return new BigDecimal[]{null, null};
        String[] parts = location.split(",", -1);
        if (parts.length != 2) return new BigDecimal[]{null, null};
        try {
            return new BigDecimal[]{new BigDecimal(parts[0].trim()), new BigDecimal(parts[1].trim())};
        } catch (NumberFormatException error) {
            return new BigDecimal[]{null, null};
        }
    }

    private static BigDecimal distance(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static long parseCount(String value) {
        if (!StringUtils.hasText(value)) return 0L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return 0L;
        }
    }

    private void cache(CacheKey key, NearbyPoiPage page, long nowNanos) {
        Duration ttl = properties.getNearbyCacheTtl();
        int maxEntries = properties.getNearbyCacheMaxEntries();
        if (ttl == null || ttl.isZero() || ttl.isNegative() || maxEntries <= 0) return;
        nearbyCache.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() <= nowNanos);
        if (nearbyCache.size() >= maxEntries) {
            nearbyCache.keySet().stream().findFirst().ifPresent(nearbyCache::remove);
        }
        nearbyCache.put(key, new CacheEntry(page, nowNanos + ttl.toNanos()));
    }

    private static CacheKey cacheKey(String keyword, BigDecimal longitude, BigDecimal latitude,
                                     int radiusMeters, int page, int pageSize) {
        return new CacheKey(normalizeCoordinate(longitude), normalizeCoordinate(latitude),
                StringUtils.hasText(keyword) ? keyword.trim().toLowerCase(Locale.ROOT) : "",
                radiusMeters, page, pageSize);
    }

    private static BigDecimal normalizeCoordinate(BigDecimal value) {
        // 约11米网格吸收手机定位抖动；门店排序仍由首次真实坐标请求高德计算。
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static int toMillis(Duration duration) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, duration.toMillis()));
    }

    private record CacheKey(BigDecimal longitude, BigDecimal latitude, String keyword,
                            int radiusMeters, int page, int pageSize) {
    }

    private record CacheEntry(NearbyPoiPage page, long expiresAtNanos) {
    }
}
