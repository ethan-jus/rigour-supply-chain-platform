package com.rigour.erp.infrastructure.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.rigour.erp.infrastructure.config.ProductMediaAccessProperties;
import org.junit.jupiter.api.Test;

class CosProductMediaUrlResolverTest {

    @Test
    void rejectsNonPositiveCosTimeouts() {
        ProductMediaAccessProperties properties = validProperties();
        properties.getCos().setSocketTimeoutMs(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new CosProductMediaUrlResolver(properties));

        assertEquals("商品图片 COS 连接和读取超时必须大于0", error.getMessage());
    }

    @Test
    void rejectsObjectKeyOutsideProductImageDirectory() {
        assertThrows(IllegalArgumentException.class,
                () -> CosProductMediaUrlResolver.validateKey("tenant-id", "tenant-id/other/image.jpg",
                        "product-images"));
    }

    private static ProductMediaAccessProperties validProperties() {
        ProductMediaAccessProperties properties = new ProductMediaAccessProperties();
        ProductMediaAccessProperties.Cos cos = properties.getCos();
        cos.setObjectPrefix("product-images");
        cos.setRegion("ap-test");
        cos.setBucket("rigour-erp-test-1250000000");
        cos.setSecretId("test-secret-id");
        cos.setSecretKey("test-secret-key");
        return properties;
    }
}
