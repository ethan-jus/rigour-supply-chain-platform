package com.rigour.integration.application.port.out;

/**
 * 订货宝商品图片的私有对象存储端口。
 *
 * <p>调用方只提交租户隔离后的对象 key 和图片字节；实现不得生成公开 URL。ERP 和 Portal
 * 后续按 key 生成短时授权 URL，避免把第三方 URL 或永久 COS URL 写入业务数据。</p>
 */
public interface ProductMediaStorage {

    /** 判断对象是否仍存在；用于数据库快照未变但 COS 对象被删除时触发补传。 */
    boolean exists(String tenantId, String objectKey);

    void put(String tenantId, String objectKey, String originalName,
             String contentType, byte[] content);
}
