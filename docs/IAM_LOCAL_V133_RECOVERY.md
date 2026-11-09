# IAM 本机 V133 / V147 临时启动说明

2026-10-15 核实：共享 DEV 的 `rigour_iam.flyway_schema_history` 已成功执行
`V133__iam_native_app_session_client.sql`，校验值为 `-1799016397`。
该脚本来自其他开发者，当前代码与远端分支尚未包含原文件。本次薪酬导航迁移已顺延至
`V134__hr_payroll_scheme_navigation.sql`，不能拿它或空脚本替代原版 V133。

## 临时启动

在 IDEA 的 `IamApplication` 运行配置中保留现有环境变量，Program arguments 使用：

```text
--spring.profiles.active=dev,local --rigour.iam.flyway.allow-missing-v133=true
```

临时开关默认关闭，仅在 `local` 且没有 `prod` profile 时装配。
`KnownMissingV133MigrationStrategy` 先执行完整预检，仅放行文件名、描述、版本、校验值及
成功状态均匹配已核实记录的缺失项。待执行迁移正常运行；其他缺失、失败、未来版本或
校验差异仍阻止启动。没有原版 V133 执行记录的新库不能使用此例外跳过迁移。

2026-11-03 为先恢复本机启动，按用户要求将以下已成功执行的记录加入临时过滤范围：

| 版本 | 文件 | 描述 | 校验值 |
|---|---|---|---|
| 147 | `V147__hr_employee_delete_permission.sql` | `hr employee delete permission` | `-1379744324` |

本次只读核实的目标为 `192.168.12.7:13306/rigour_iam`，V147 执行时间为
`2026-11-03 09:07:41`（数据库记录值）。本地最高版本为 V146 时，Flyway 会将 V147
识别为未来版本，过滤仍须通过上述精确核对。类名和参数沿用 V133 以兼容现有 IDEA 配置。
原版 V133 已在当前源码中补齐；V147 原文件仍待同步，这个例外不表示迁移源码已经完整。

本路径不调用 `repair`，不关闭 Flyway，不修改已执行的历史记录，也不生成占位 SQL。
启动日志会明确提示临时例外，不应把临时启动成功视为源码与数据库迁移完全一致。

## 补齐原文件后

1. 将同事尚未同步的原版 SQL 原样放入 `iam-server/src/main/resources/db/migration/`，校验值应与数据库一致。
2. 移除 `--rigour.iam.flyway.allow-missing-v133=true`，按正常方式重启并验证迁移。
3. 删除临时配置类 `LocalIamMigrationRecoveryConfiguration`、策略类
   `KnownMissingV133MigrationStrategy`、对应测试和本文档。

数据库当前表结构可以导出作核对材料，但不能由此还原原脚本的注释、执行顺序、数据迁移
或精确内容，不能将反向生成 SQL 声称为已执行的原版 V133。

## 2026-11-03 验证

- 定向测试 37 项通过，覆盖 Spring Boot 初始化、历史不变、待执行迁移仅运行一次，以及未知版本、失败状态和校验差异拦截。
- 使用当前源码的 V139 历史资源选择及临时过滤规则，对真实 DEV 库进行只读校验：153 项迁移校验通过，待执行迁移为 0。
- 本次验证没有调用真实库的 `migrate` / `repair`，没有启动或重启服务。实际 IAM 启动与健康检查待用户在现有 IDEA 运行项重启后确认。

## 2026-10-15 历史验证

- 临时策略、环境开关与 IAM 分层检查共 17 项测试通过。
- 共享 DEV 的 V134 已成功执行，V133 的文件名、校验值、成功状态及原执行时间保持不变。
- 使用现有 IDEA 环境变量、`dev,local` 与临时参数启动，26881 健康检查返回 HTTP 200、`UP`。
- 历史迁移与字符集基线检查通过；未进行前端业务验收。
