package com.rigour.shared.core.sync;

/** 外部来源编码约定：Integration 使用第三方短码，领域表使用业务展示码。 */
public final class ExternalSourceCodes {
    public static final String INTEGRATION_DHB = "DHB";
    public static final String DOMAIN_DINGHUOBAO = "DINGHUOBAO";

    private ExternalSourceCodes() {
    }

    public static String toDomainSourceSystem(String integrationSourceSystem) {
        if (integrationSourceSystem == null || integrationSourceSystem.isBlank()) return null;
        String normalized = integrationSourceSystem.strip().toUpperCase(java.util.Locale.ROOT);
        return INTEGRATION_DHB.equals(normalized) ? DOMAIN_DINGHUOBAO : normalized;
    }

    public static String toIntegrationSourceSystem(String domainSourceSystem) {
        if (domainSourceSystem == null || domainSourceSystem.isBlank()) return null;
        String normalized = domainSourceSystem.strip().toUpperCase(java.util.Locale.ROOT);
        return DOMAIN_DINGHUOBAO.equals(normalized) ? INTEGRATION_DHB : normalized;
    }
}
