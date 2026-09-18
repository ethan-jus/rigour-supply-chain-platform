package com.rigour.order.api.v1;

import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 已有订单规则的租户参数，只开放有实际消费者的目录。 */
public interface OrderParameterApi {
    String BASE_PATH = "/api/v1/orders/settings/parameters";

    record Parameter(
            String code,
            String name,
            String group,
            String value,
            String defaultValue,
            String description,
            String effect,
            int minimum,
            int maximum,
            long revision) {}

    record Change(String value, long revision, String reason) {}

    @GetMapping(BASE_PATH)
    ApiResponse<List<Parameter>> list();

    @PutMapping(BASE_PATH + "/{code}")
    ApiResponse<Parameter> save(@PathVariable String code, @RequestBody Change command);
}
