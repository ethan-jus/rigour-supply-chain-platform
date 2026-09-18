package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProductImageObjectKeyFactoryTest {

    @Test
    void usesConfiguredPrefixAndContentHashInObjectKey() {
        ProductImageObjectKeyFactory factory = new ProductImageObjectKeyFactory("catalog/product-images");

        String objectKey = factory.generate("tenant-id", "spu/1", "image/2", 1,
                "image-content".getBytes(StandardCharsets.UTF_8), "main.png", "image/png");

        assertThat(objectKey).startsWith("tenant-id/catalog/product-images/spu_1/image_2/");
        assertThat(objectKey).endsWith(".png");
    }

    @Test
    void rejectsUnsafeConfiguredPrefix() {
        assertThrows(IllegalStateException.class,
                () -> new ProductImageObjectKeyFactory("../product-images"));
    }
}
