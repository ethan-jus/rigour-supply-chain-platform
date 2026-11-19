package com.rigour.merchant.api.v1.model;

/** 仅核验调用方提供的编码，不返回未授权地区的名称或档案。 */
public record CostCityCheckView(String code, boolean configured, boolean allowed, String issue) {
    public CostCityCheckView(String code, boolean configured, boolean allowed) {
        this(code, configured, allowed, null);
    }
}
