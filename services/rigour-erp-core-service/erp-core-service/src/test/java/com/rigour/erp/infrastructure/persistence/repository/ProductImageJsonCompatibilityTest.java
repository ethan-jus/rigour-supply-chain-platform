package com.rigour.erp.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.rigour.erp.domain.model.product.Product;
import com.rigour.erp.domain.model.product.ProductImage;
import com.rigour.erp.domain.model.product.Sku;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductImageJsonCompatibilityTest {

    @Test
    void managementReaderAcceptsLegacyStringImageKeys() throws Exception {
        String legacyJson = """
                ["tenant-id/product-images/1168437/2372121/main.png",
                 "tenant-id/product-images/1168437/2372122/detail.png"]
                """;

        List<?> images = parseManagementImages(legacyJson);

        assertThat(images).hasSize(2);
        assertThat(accessor(images.get(0), "imageKey"))
                .isEqualTo("tenant-id/product-images/1168437/2372121/main.png");
        assertThat(accessor(images.get(0), "imageTypeCode")).isEqualTo("MAIN");
        assertThat(accessor(images.get(1), "imageTypeCode")).isEqualTo("DETAIL");
    }

    @Test
    void productSyncWritesTypedMainAndDetailImageObjects() throws Exception {
        Product product = productWithImages(
                "tenant-id/product-images/1168437/2372121/main.png",
                List.of(
                        new ProductImage("2372122", "1168437", "detail.png", "detail.png",
                                2, "tenant-id/product-images/1168437/2372122/detail.png"),
                        new ProductImage("2372121", "1168437", "main.png", "main.png",
                                1, "tenant-id/product-images/1168437/2372121/main.png")));

        String json = syncImageKeysJson(product);
        List<?> images = parseManagementImages(json);

        assertThat(json).doesNotStartWith("[\"");
        assertThat(images).hasSize(2);
        assertThat(accessor(images.get(0), "imageKey"))
                .isEqualTo("tenant-id/product-images/1168437/2372121/main.png");
        assertThat(accessor(images.get(0), "imageTypeCode")).isEqualTo("MAIN");
        assertThat(accessor(images.get(0), "ordinal")).isEqualTo(0);
        assertThat(accessor(images.get(1), "imageTypeCode")).isEqualTo("DETAIL");
        assertThat(accessor(images.get(1), "ordinal")).isEqualTo(1);
    }

    private static Product productWithImages(String mainImageKey, List<ProductImage> images) {
        return new Product("1168437", "DHB-001", "Test Product", "T", "NORMAL", "barcode",
                "箱", "cat-1", "brand-1", "model", null, null, null,
                mainImageKey, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, images, Map.<String, String>of(), List.<Sku>of(),
                Map.<String, Object>of(), "hash");
    }

    private static List<?> parseManagementImages(String json) throws Exception {
        Method method = MybatisPlusProductManagementRepository.class
                .getDeclaredMethod("parseImages", String.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(null, json);
    }

    private static String syncImageKeysJson(Product product) throws Exception {
        Method method = MybatisPlusProductMasterDataRepository.class
                .getDeclaredMethod("imageKeysJson", Product.class);
        method.setAccessible(true);
        return (String) method.invoke(null, product);
    }

    private static Object accessor(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
