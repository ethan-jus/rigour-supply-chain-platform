package com.rigour.order.infrastructure.integration;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.*;
import java.util.function.Supplier;

/** 仅复用当前HTTP请求的只读引用，不跨请求缓存权限、租户资料或业务数据。 */
final class LogisticsRequestCache {
    private LogisticsRequestCache() {}

    @SuppressWarnings("unchecked")
    static <T> T get(String tenant, String action, String kind, Object key, Supplier<T> read) {
        var request = RequestContextHolder.getRequestAttributes();
        if (request == null) return read.get();
        String attribute = LogisticsRequestCache.class.getName();
        var values =
                (Map<List<Object>, Object>)
                        request.getAttribute(attribute, RequestAttributes.SCOPE_REQUEST);
        if (values == null) {
            values = new HashMap<>();
            request.setAttribute(attribute, values, RequestAttributes.SCOPE_REQUEST);
        }
        var cacheKey = List.of(tenant, action, kind, key);
        if (!values.containsKey(cacheKey)) values.put(cacheKey, read.get());
        return (T) values.get(cacheKey);
    }
}
