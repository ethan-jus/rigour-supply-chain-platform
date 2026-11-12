package com.rigour.order.application.port.out;

public interface CostAttachmentStorage {
    String upload(String tenant, String entry, String contentType, byte[] bytes);

    String url(String key);

    String downloadUrl(String key, String name);

    void discard(String key);
}
