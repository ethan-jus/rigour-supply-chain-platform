package com.rigour.sales.temporarycheckin;

import com.rigour.sales.infrastructure.config.AmapProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

/** 高德 Web 服务坐标转换与逆地理编码适配器；密钥不进入前端、业务数据或日志。 */
@Component
public class AmapTemporaryCheckinReverseGeocoder implements TemporaryCheckinReverseGeocoder {

    private static final Logger log = LoggerFactory.getLogger(AmapTemporaryCheckinReverseGeocoder.class);
    private static final int MAX_ADDRESS_LENGTH = 512;

    private final RestClient restClient;
    private final AmapProperties properties;
    private final ObjectMapper objectMapper;

    @Autowired
    public AmapTemporaryCheckinReverseGeocoder(
            RestClient.Builder builder,
            AmapProperties properties,
            ObjectMapper objectMapper) {
        this(createRestClient(builder, properties), properties, objectMapper);
    }

    AmapTemporaryCheckinReverseGeocoder(
            RestClient restClient,
            AmapProperties properties,
            ObjectMapper objectMapper) {
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
    public GeocodeResult resolve(BigDecimal longitude, BigDecimal latitude) {
        if (!StringUtils.hasText(properties.getWebKey())) {
            return GeocodeResult.keyMissing();
        }
        long startedAt = System.nanoTime();
        try {
            Coordinates amap = convertGps(longitude, latitude);
            GeocodeResult result = reverseGeocode(amap);
            log.info("临时打卡逆地理编码成功 endpoint=/geocode/regeo elapsedMs={}",
                    (System.nanoTime() - startedAt) / 1_000_000);
            return result;
        } catch (AmapBusinessException exception) {
            log.warn("临时打卡逆地理编码业务失败 endpoint={} code={}",
                    exception.endpoint(), exception.code());
            return GeocodeResult.failed(limit(exception.code(), 64));
        } catch (RestClientResponseException exception) {
            log.warn("临时打卡逆地理编码HTTP失败 httpStatus={}", exception.getStatusCode().value());
            return GeocodeResult.failed("AMAP_HTTP_" + exception.getStatusCode().value());
        } catch (RestClientException exception) {
            log.warn("临时打卡逆地理编码连接失败 reason={}", exception.getClass().getSimpleName());
            return GeocodeResult.failed("AMAP_CONNECTION_FAILED");
        } catch (RuntimeException exception) {
            log.warn("临时打卡逆地理编码响应解析失败 reason={}", exception.getClass().getSimpleName());
            return GeocodeResult.failed("AMAP_RESPONSE_INVALID");
        }
    }

    private Coordinates convertGps(BigDecimal longitude, BigDecimal latitude) {
        String gps = coordinate(longitude) + "," + coordinate(latitude);
        String body = restClient.get().uri(uriBuilder -> uriBuilder
                .path("/assistant/coordinate/convert")
                .queryParam("key", properties.getWebKey())
                .queryParam("locations", gps)
                .queryParam("coordsys", "gps")
                .queryParam("output", "JSON")
                .build()).retrieve().body(String.class);
        JsonNode root = objectMapper.readTree(body);
        requireSuccess(root, "/assistant/coordinate/convert");
        String converted = text(root.path("locations"));
        if (!StringUtils.hasText(converted)) {
            throw new AmapBusinessException("/assistant/coordinate/convert", "AMAP_CONVERT_EMPTY");
        }
        String first = converted.split(";", -1)[0];
        String[] parts = first.split(",", -1);
        if (parts.length != 2) {
            throw new AmapBusinessException("/assistant/coordinate/convert", "AMAP_CONVERT_INVALID");
        }
        try {
            return new Coordinates(new BigDecimal(parts[0].trim()).setScale(6, RoundingMode.HALF_UP),
                    new BigDecimal(parts[1].trim()).setScale(6, RoundingMode.HALF_UP));
        } catch (NumberFormatException exception) {
            throw new AmapBusinessException("/assistant/coordinate/convert", "AMAP_CONVERT_INVALID");
        }
    }

    private GeocodeResult reverseGeocode(Coordinates coordinates) {
        String location = coordinates.longitude().toPlainString() + "," + coordinates.latitude().toPlainString();
        String body = restClient.get().uri(uriBuilder -> uriBuilder
                .path("/geocode/regeo")
                .queryParam("key", properties.getWebKey())
                .queryParam("location", location)
                .queryParam("radius", 1000)
                .queryParam("extensions", "all")
                .queryParam("roadlevel", 0)
                .queryParam("output", "JSON")
                .build()).retrieve().body(String.class);
        JsonNode root = objectMapper.readTree(body);
        requireSuccess(root, "/geocode/regeo");
        JsonNode regeocode = root.path("regeocode");
        JsonNode component = regeocode.path("addressComponent");
        String formatted = text(regeocode.path("formatted_address"));
        String province = text(component.path("province"));
        String city = text(component.path("city"));
        String district = text(component.path("district"));
        String township = text(component.path("township"));
        String adcode = text(component.path("adcode"));
        String address = displayAddress(formatted, district, regeocode);
        if (!StringUtils.hasText(address)) {
            throw new AmapBusinessException("/geocode/regeo", "AMAP_ADDRESS_EMPTY");
        }
        return new GeocodeResult("RESOLVED", limit(address, MAX_ADDRESS_LENGTH),
                limit(formatted, MAX_ADDRESS_LENGTH), limit(adcode, 16), limit(province, 64),
                limit(city, 64), limit(district, 64), limit(township, 128),
                coordinates.longitude(), coordinates.latitude(), null);
    }

    private static String displayAddress(String formatted, String district, JsonNode regeocode) {
        JsonNode roadInters = regeocode.path("roadinters");
        if (roadInters.isArray() && roadInters.size() > 0) {
            JsonNode roadInter = roadInters.get(0);
            String firstName = text(roadInter.path("first_name"));
            String secondName = text(roadInter.path("second_name"));
            if (StringUtils.hasText(firstName) && StringUtils.hasText(secondName)) {
                String relation = value(district) + firstName + "与" + secondName + "交叉口"
                        + value(text(roadInter.path("direction")))
                        + distance(text(roadInter.path("distance")));
                return joinAddress(formatted, relation);
            }
        }
        JsonNode pois = regeocode.path("pois");
        if (pois.isArray() && pois.size() > 0) {
            JsonNode poi = pois.get(0);
            String name = text(poi.path("name"));
            if (StringUtils.hasText(name)) {
                String relation = "近" + name + value(text(poi.path("direction")))
                        + distance(text(poi.path("distance")));
                return joinAddress(formatted, relation);
            }
        }
        return formatted;
    }

    private static String joinAddress(String formatted, String relation) {
        if (!StringUtils.hasText(formatted)) return relation;
        if (!StringUtils.hasText(relation) || formatted.contains(relation)) return formatted;
        return formatted + "；" + relation;
    }

    private static String distance(String raw) {
        if (!StringUtils.hasText(raw)) return "";
        try {
            return new BigDecimal(raw).setScale(0, RoundingMode.HALF_UP).toPlainString() + "米";
        } catch (NumberFormatException exception) {
            return "";
        }
    }

    private static void requireSuccess(JsonNode root, String endpoint) {
        if (root == null || !"1".equals(text(root.path("status")))) {
            String code = root == null ? null : text(root.path("infocode"));
            String info = root == null ? null : text(root.path("info"));
            throw new AmapBusinessException(endpoint,
                    StringUtils.hasText(code) ? "AMAP_" + code : "AMAP_" + value(info, "FAILED"));
        }
    }

    private static String coordinate(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() && StringUtils.hasText(node.asText()) ? node.asText().trim() : null;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String value(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    private static int toMillis(java.time.Duration duration) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, duration.toMillis()));
    }

    private record Coordinates(BigDecimal longitude, BigDecimal latitude) { }

    private static final class AmapBusinessException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String endpoint;
        private final String code;

        private AmapBusinessException(String endpoint, String code) {
            super(code);
            this.endpoint = endpoint;
            this.code = code;
        }

        private String endpoint() {
            return endpoint;
        }

        private String code() {
            return code;
        }
    }
}
