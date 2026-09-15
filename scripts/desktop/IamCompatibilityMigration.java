import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;

/** 台式机首次导入后的受控历史补充迁移；只允许补齐四个兼容版本，不修改旧历史。 */
class IamCompatibilityMigration {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://127.0.0.1:13306/rigour_iam?sslMode=DISABLED&allowPublicKeyRetrieval=true";
        String user = "rigour_iam_migrator";
        String password = System.getenv("IAM_DB_MIGRATOR_PASSWORD");
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            var result = statement.executeQuery("SELECT COUNT(*) FROM flyway_schema_history "
                    + "WHERE version IN ('51.1','52.1','74.1','75.1') AND success=1");
            result.next();
            if (result.getInt(1) == 4) {
                System.out.println("IAM 历史补充迁移已经完成，日常发布不再启用乱序迁移。");
                return;
            }
        }
        Flyway flyway = Flyway.configure().dataSource(url, user, password)
                .locations("filesystem:" + args[0]).outOfOrder(true).cleanDisabled(true)
                .ignoreMigrationPatterns("*:future", "*:pending").load();
        Set<String> pending = Arrays.stream(flyway.info().pending())
                .map(info -> info.getVersion().getVersion()).collect(Collectors.toSet());
        if (pending.isEmpty() || !Set.of("51.1", "52.1", "74.1", "75.1").containsAll(pending)) {
            throw new IllegalStateException("待执行迁移不符合补丁白名单，未迁移：" + pending);
        }
        // 先限制待执行版本，再允许这四项 pending；原有已执行版本仍严格校验 checksum。
        flyway.validate();
        if (args.length > 1 && "--check-only".equals(args[1])) {
            System.out.println("IAM 历史校验及补丁白名单检查通过，未执行迁移：" + pending);
            return;
        }
        var result = flyway.migrate();
        if (result.migrationsExecuted != pending.size()) {
            throw new IllegalStateException("实际迁移数量与白名单不一致");
        }
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            var check = statement.executeQuery("SELECT check_clause FROM information_schema.check_constraints "
                    + "WHERE constraint_schema='rigour_iam' AND constraint_name='ck_iam_resource_status'");
            if (!check.next() || check.getString(1).contains("INACTIVE")) {
                throw new IllegalStateException("资源状态约束未恢复，停止发布");
            }
        }
        System.out.println("已仅补齐 IAM 51.1/52.1/74.1/75.1，并确认严格状态约束恢复。");
    }
}
