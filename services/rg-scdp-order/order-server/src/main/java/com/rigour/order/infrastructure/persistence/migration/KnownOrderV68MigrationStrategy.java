package com.rigour.order.infrastructure.persistence.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;

import java.util.Arrays;
import java.util.Objects;

/** 仅在本机临时放行已核实的原版 V68 缺失，保留其历史和其他迁移校验。 */
public final class KnownOrderV68MigrationStrategy implements FlywayMigrationStrategy {
    private static final Logger LOG = LoggerFactory.getLogger(KnownOrderV68MigrationStrategy.class);
    private static final MigrationVersion VERSION = MigrationVersion.fromVersion("68");
    private static final String SCRIPT = "V68__payment_owner_evidence.sql";
    private static final String DESCRIPTION = "payment owner evidence";
    private static final int CHECKSUM = 1727099056;

    /** 只读预检，供启动和本机维护核对共用，不执行 migrate/repair。 */
    public boolean hasKnownMissingHistory(Flyway flyway) {
        var validation =
                Flyway.configure()
                        .configuration(flyway.getConfiguration())
                        .ignoreMigrationPatterns("*:pending")
                        .load();
        var result = validation.validateWithResult();
        if (result.validationSuccessful) return false;
        var migrations = validation.info().all();
        boolean known =
                !result.invalidMigrations.isEmpty()
                        && result.invalidMigrations.stream()
                                .allMatch(
                                        invalid -> {
                                            if (invalid.errorDetails.errorCode
                                                            != CoreErrorCode
                                                                    .APPLIED_VERSIONED_MIGRATION_NOT_RESOLVED
                                                    || !VERSION.equals(
                                                            MigrationVersion.fromVersion(
                                                                    invalid.version))) return false;
                                            var matches =
                                                    Arrays.stream(migrations)
                                                            .filter(
                                                                    info ->
                                                                            VERSION.equals(
                                                                                    info
                                                                                            .getVersion()))
                                                            .toList();
                                            if (matches.size() != 1) return false;
                                            var info = matches.getFirst();
                                            return (info.getState()
                                                                    == MigrationState
                                                                            .MISSING_SUCCESS
                                                            || info.getState()
                                                                    == MigrationState
                                                                            .FUTURE_SUCCESS)
                                                    && "SQL".equals(info.getType().name())
                                                    && SCRIPT.equals(info.getScript())
                                                    && DESCRIPTION.equals(info.getDescription())
                                                    && Objects.equals(CHECKSUM, info.getChecksum());
                                        });
        if (!known)
            throw new FlywayException(
                    "订单服务本机过滤仅允许已核实的原版V68缺失，其他迁移差异必须修复：" + result.getAllErrorMessages());
        return true;
    }

    @Override
    public void migrate(Flyway flyway) {
        if (!hasKnownMissingHistory(flyway)) {
            flyway.migrate();
            return;
        }
        LOG.warn(
                "本机临时放行原版V68缺失，已核对文件名、描述、成功状态及校验值；保留原历史，继续执行本地前向迁移。原件合入后关闭"
                        + " allow-missing-v68。");
        Flyway.configure()
                .configuration(flyway.getConfiguration())
                .ignoreMigrationPatterns("versioned:missing", "versioned:future")
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .cleanDisabled(true)
                .load()
                .migrate();
    }
}
