# Nacos 配置模板

这些文件是手工发布到共享 DEV Nacos 的 Data ID 内容，Namespace 使用：

```text
名称：dev
ID：3aa03547-8948-4254-bd94-47c630db128b
Group：DEFAULT_GROUP
```

复制时去掉文件名中的 `.example`：

| 文件 | Nacos Data ID | 数据库密码环境变量 |
|---|---|---|
| `rigour-tenant-iam-service.example.yml` | `rigour-tenant-iam-service.yaml` | `IAM_DB_APP_PASSWORD` / `IAM_DB_MIGRATOR_PASSWORD` |
| `rigour-integration-migration-service.example.yml` | `rigour-integration-migration-service.yaml` | `INTEGRATION_DB_APP_PASSWORD` / `INTEGRATION_DB_MIGRATOR_PASSWORD` |
| `rigour-merchant-crm-service.example.yml` | `rigour-merchant-crm-service.yaml` | `CRM_DB_APP_PASSWORD` / `CRM_DB_MIGRATOR_PASSWORD` |
| `rigour-erp-core-service.example.yml` | `rigour-erp-core-service.yaml` | `ERP_DB_APP_PASSWORD` / `ERP_DB_MIGRATOR_PASSWORD` |
| `rigour-order-center-service.example.yml` | `rigour-order-center-service.yaml` | `ORDER_DB_APP_PASSWORD` / `ORDER_DB_MIGRATOR_PASSWORD` |
| `rigour-sales-work-service.example.yml` | `rigour-sales-work-service.yaml` | `SALES_WORK_DB_APP_PASSWORD` / `SALES_WORK_DB_MIGRATOR_PASSWORD` |
| `rigour-ai-agent-service.example.yml` | `rigour-ai-agent-service.yaml` | `AI_DB_APP_PASSWORD` / `AI_DB_MIGRATOR_PASSWORD` |
| `rigour-analytics-bi-service.example.yml` | `rigour-analytics-bi-service.yaml` | `BI_DB_APP_PASSWORD` / `BI_DB_MIGRATOR_PASSWORD` |
| `rigour-hr-payroll-service.example.yml` | `rigour-hr-payroll-service.yaml` | `HR_DB_APP_PASSWORD` / `HR_DB_MIGRATOR_PASSWORD` |
| `rigour-city-operations-service.example.yml` | `rigour-city-operations-service.yaml` | `CITY_DB_APP_PASSWORD` / `CITY_DB_MIGRATOR_PASSWORD` |
| `rigour-channel-agent-service.example.yml` | `rigour-channel-agent-service.yaml` | `CHANNEL_DB_APP_PASSWORD` / `CHANNEL_DB_MIGRATOR_PASSWORD` |

9 个新领域库目前只有空 Schema，没有业务迁移，所以模板将 `spring.flyway.enabled` 设为 `false`。完成某个服务的 V1 迁移并把 JDBC/Flyway 依赖接入后，再在该服务的 Nacos Data ID 中改为 `true`。

启动时只需要在本机或 IDEA Run Configuration 设置对应的两个密码变量；Nacos 只保存 `${...}` 占位符，不解析也不保存密码明文。所有领域服务还需要注入同一份 `RIGOUR_CONTEXT_TRUST_KEY_V1`。

Gateway 不拥有业务数据库，因此只有安全和路由配置，不增加 datasource 配置。
