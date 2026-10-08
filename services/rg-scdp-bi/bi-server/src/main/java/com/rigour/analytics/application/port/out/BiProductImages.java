package com.rigour.analytics.application.port.out;

import java.util.List;
import java.util.Map;

/** 商品主图只读补充，调用方仅传入已经通过 BI 数据范围校验的商品。 */
public interface BiProductImages {
    Map<String, String> urls(String tenantId, List<String> productIds);
}
