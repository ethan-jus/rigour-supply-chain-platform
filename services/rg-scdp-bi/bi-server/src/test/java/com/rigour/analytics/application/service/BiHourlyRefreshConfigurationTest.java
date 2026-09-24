package com.rigour.analytics.application.service;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class BiHourlyRefreshConfigurationTest {
    @Test
    void runtimeProfilesParseWithoutDuplicateConfigurationKeys() throws Exception {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        var yaml = new Yaml(new SafeConstructor(options));
        for (String profile : List.of("application.yml", "application-dev.yml", "application-local.yml")) {
            try (var input = Files.newInputStream(Path.of("src/main/resources", profile))) {
                for (var document : yaml.loadAll(input)) {
                    org.assertj.core.api.Assertions.assertThat(document).isNotNull();
                }
            }
        }
    }
}
