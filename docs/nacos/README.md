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
| `rigour-business-settings-service.example.yml` | `rigour-business-settings-service.yaml` | `BUSINESS_SETTINGS_DB_APP_PASSWORD` / `BUSINESS_SETTINGS_DB_MIGRATOR_PASSWORD` |

ERP、Order Center、Sales Work 和 Business Settings 已包含正式业务迁移，其模板将 `spring.flyway.enabled` 设为 `true`。其他仍为空 Schema 的领域服务保持 `false`；完成首个迁移并接入 JDBC/Flyway 后再切换。模板变更不等于共享 DEV Nacos 已发布。

启动时只需要在本机或 IDEA Run Configuration 设置对应的两个数据库密码变量；Nacos 只保存 `${...}` 占位符，不解析也不保存密码明文。所有领域服务还需要注入同一份 `RIGOUR_CONTEXT_TRUST_KEY_V1`；该变量的属性映射保留在各服务本地 `application.yml`，不写入 Nacos Data ID。

Sales Work、Integration 和 ERP Core 共用同一个 COS 私桶，但按对象 Key 前缀隔离用途：Sales Work
使用 `tenantId/visits/`，商品图片使用 `tenantId/product-images/`。当前共享 DEV 桶为
`ap-beijing / rigour-sales-recordings-1361731487`。三个服务仍使用各自的 Secret ID、Secret Key
和可选 Session Token，并分别授予所需的前缀权限；商品图片前缀由各自配置中的
`rigour.*.product-media.cos.object-prefix` 管理，三端必须保持一致。缺失配置时服务会直接启动失败，
不会降级为本地文件存储。

Gateway 不拥有业务数据库，因此只有安全和路由配置，不增加 datasource 配置。
