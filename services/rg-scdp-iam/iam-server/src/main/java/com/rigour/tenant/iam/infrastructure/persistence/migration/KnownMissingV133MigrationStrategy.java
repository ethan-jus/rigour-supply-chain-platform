package com.rigour.tenant.iam.infrastructure.persistence.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.ValidateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;

import java.util.Arrays;
import java.util.Objects;

/**
 * 仅容许共享 DEV 已成功执行、但尚未同步到本机的原生 App V133 缺失。
 *
 * <p>不伪造迁移文件、不修改历史记录。TODO：同事同步原版 V133 后删除本策略及其配置入口。
 */
public final class KnownMissingV133MigrationStrategy implements FlywayMigrationStrategy {
    private static final Logger LOG =
            LoggerFactory.getLogger(KnownMissingV133MigrationStrategy.class);
    private static final MigrationVersion VERSION = MigrationVersion.fromVersion("133");
    private static final String SCRIPT = "V133__iam_native_app_session_client.sql";
    private static final String DESCRIPTION = "iam native app session client";
    private static final int CHECKSUM = -1799016397;

    @Override
    public void migrate(Flyway flyway) {
        // 预检只忽略待执行迁移，确保其他缺失、失败、未来版本和校验差异全部可见。
        Flyway validation =
                Flyway.configure()
                        .configuration(flyway.getConfiguration())
                        .ignoreMigrationPatterns("*:pending")
                        .load();
        ValidateResult result = validation.validateWithResult();
        MigrationInfo original =
                Arrays.stream(validation.info().all())
                        .filter(info -> VERSION.equals(info.getVersion()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new FlywayException(
                                                "本机临时启动要求数据库已有原版 V133，或本地已补齐该文件；不能用于跳过新库迁移。"));

        if (!SCRIPT.equals(original.getScript())
                || !DESCRIPTION.equals(original.getDescription())
                || !Objects.equals(CHECKSUM, original.getChecksum())) {
            throw new FlywayException("V133 与已核实的原生 App 迁移记录不一致，禁止临时启动。");
        }
        if (result.validationSuccessful && original.getState().isResolved()) {
            LOG.info("原版 V133 已补齐，执行正常迁移；请移除 allow-missing-v133 临时参数。");
            flyway.migrate();
            return;
        }
        if (!isOnlyKnownMissingMigration(result, original)) {
            throw new FlywayException(
                    "仅允许缺失已核实的原版 V133，其他迁移问题必须修复：" + result.getAllErrorMessages());
        }

        LOG.warn("本机临时允许缺失 {}（已执行校验值 {}）；保留数据库历史，其他迁移正常校验执行。请尽快补齐原文件。", SCRIPT, CHECKSUM);
        // Flyway 的 missing 模式不能限定版本，因此只能在上面的精确预检通过后使用。
        Flyway.configure()
                .configuration(flyway.getConfiguration())
                .ignoreMigrationPatterns("versioned:missing")
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    private static boolean isOnlyKnownMissingMigration(
            ValidateResult result, MigrationInfo original) {
        if (result.validationSuccessful
                || original.getState() != MigrationState.MISSING_SUCCESS
                || result.invalidMigrations.size() != 1) {
            return false;
        }
        var invalid = result.invalidMigrations.getFirst();
        return VERSION.toString().equals(invalid.version)
                && invalid.errorDetails.errorCode
                        == CoreErrorCode.APPLIED_VERSIONED_MIGRATION_NOT_RESOLVED;
    }
}
