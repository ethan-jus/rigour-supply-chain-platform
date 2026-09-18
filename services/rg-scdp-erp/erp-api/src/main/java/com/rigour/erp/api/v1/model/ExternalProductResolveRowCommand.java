package com.rigour.erp.api.v1.model;

/** 外部订单行中的商品引用。 */
public record ExternalProductResolveRowCommand(
        String referenceId,
        String productCode,
        String variantCode,
        String productName,
        String specification) {
    public ExternalProductResolveRowCommand {
        referenceId = text(referenceId, 128);
        productCode = text(productCode, 128);
        variantCode = text(variantCode, 128);
        productName = text(productName, 200);
        specification = text(specification, 500);
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String text = value.strip();
        return text.length() > max ? text.substring(0, max) : text;
    }
}
