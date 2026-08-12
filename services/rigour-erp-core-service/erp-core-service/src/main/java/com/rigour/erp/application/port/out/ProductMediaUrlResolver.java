package com.rigour.erp.application.port.out;

/** 商品图片对象 URL 解析端口；每次本地查询按 COS key 生成最新短时 URL。 */
public interface ProductMediaUrlResolver {
    String temporaryUrl(String tenantId, String objectKey);
}
