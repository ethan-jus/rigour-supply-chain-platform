# 领域数据库运行时初始化

更新时间：2026-08-06

本文只负责共享 DEV 的数据库边界、账号和初始化方式，不代替各领域的字段级设计、Flyway 迁移和持久层实现。

2026-08-06 18:52运行时快照：`rigour_iam` 已执行V1～V21，共35张表（34张业务表加Flyway历史表）；`rigour_integration`最近已确认的基线为V1～V2、13张业务表。Sales Work V1目前只在源码和隔离MySQL中验证，尚未发布到共享DEV；其他领域库状态仍需在使用前只读核验，不能从源码推断已经发布。

## 当前范围

当前后端是一个 Gateway 加 11 个领域服务。Gateway 只负责路由和安全上下文，不拥有业务 Schema。IAM 与 Integration 已有自己的数据库和迁移，以下 9 个领域库是本次补齐的空 Schema：

| 服务 | Schema | 运行账号 | 迁移账号 | 密码环境变量 |
|---|---|---|---|---|
| `rigour-merchant-crm-service` | `rigour_crm` | `rigour_crm_app` | `rigour_crm_migrator` | `CRM_DB_APP_PASSWORD` / `CRM_DB_MIGRATOR_PASSWORD` |
| `rigour-erp-core-service` | `rigour_erp` | `rigour_erp_app` | `rigour_erp_migrator` | `ERP_DB_APP_PASSWORD` / `ERP_DB_MIGRATOR_PASSWORD` |
| `rigour-order-center-service` | `rigour_order` | `rigour_order_app` | `rigour_order_migrator` | `ORDER_DB_APP_PASSWORD` / `ORDER_DB_MIGRATOR_PASSWORD` |
| `rigour-sales-work-service` | `rigour_sales_work` | `rigour_sales_work_app` | `rigour_sales_work_migrator` | `SALES_WORK_DB_APP_PASSWORD` / `SALES_WORK_DB_MIGRATOR_PASSWORD` |
| `rigour-ai-agent-service` | `rigour_ai` | `rigour_ai_app` | `rigour_ai_migrator` | `AI_DB_APP_PASSWORD` / `AI_DB_MIGRATOR_PASSWORD` |
| `rigour-analytics-bi-service` | `rigour_bi` | `rigour_bi_app` | `rigour_bi_migrator` | `BI_DB_APP_PASSWORD` / `BI_DB_MIGRATOR_PASSWORD` |
| `rigour-hr-payroll-service` | `rigour_hr` | `rigour_hr_app` | `rigour_hr_migrator` | `HR_DB_APP_PASSWORD` / `HR_DB_MIGRATOR_PASSWORD` |
| `rigour-city-operations-service` | `rigour_city` | `rigour_city_app` | `rigour_city_migrator` | `CITY_DB_APP_PASSWORD` / `CITY_DB_MIGRATOR_PASSWORD` |
| `rigour-channel-agent-service` | `rigour_channel` | `rigour_channel_app` | `rigour_channel_migrator` | `CHANNEL_DB_APP_PASSWORD` / `CHANNEL_DB_MIGRATOR_PASSWORD` |

已有库不在本次重建范围：

- `rigour_iam`：IAM 自己管理，已有 `rigour_iam_app` 与 `rigour_iam_migrator`，执行过的迁移不能重写。
- `rigour_integration`：Integration 自己管理，已有 `rigour_integration_app` 与 `rigour_integration_migrator`，密码不轮换；详见 [`INTEGRATION_DATABASE_RUNTIME.md`](./INTEGRATION_DATABASE_RUNTIME.md)。

## 权限边界

- 运行账号只拥有本 Schema 的 `SELECT/INSERT/UPDATE/DELETE`，不拥有 DDL，不拥有其他 Schema 权限。
- 迁移账号只拥有本 Schema 的运行 DML 加 `CREATE/ALTER/INDEX/REFERENCES`，只供 Flyway 使用，不用于请求处理。
- DEV 账号默认使用 `'%'` 主机范围，但入口仍由服务器防火墙限制。生产必须收紧到应用来源网段。
- 不创建跨 Schema 外键、视图、触发器、存储过程或业务 Join。
- 数据库账号初始化脚本本身不创建业务表，也不插入租户、订单、门店、员工或测试数据。Sales Work 字段设计已冻结并新增源码 Flyway V1；其他空领域仍需在字段级设计确认后再新增各自迁移。

## 创建和验证

脚本会为 9 个缺失领域生成每个账号独立的 64 位十六进制密码，并保存到受限文件；脚本不会把密码打印到终端：

```bash
cd "/Users/ethan/myspance/rigour/B2B供应链/自动化系统构建/06_代码工程/rigour-supply-chain-platform"

# 从受控管理机执行；脚本会让操作者在终端输入当前 MySQL 管理员密码。
MYSQL_HOST=82.157.4.176 MYSQL_PORT=13306 \
  RIGOUR_DB_CREDENTIAL_FILE=/Users/ethan/.config/rigour/domain-db-dev.env \
  ./scripts/provision-domain-databases.sh
```

如果脚本在共享 DEV 服务器本机执行，把 `MYSQL_HOST` 改成 `127.0.0.1`、`MYSQL_PORT` 改成 `3306`，并把凭据文件放到 `/opt/rigour-dev/domain-db.env`。不要把管理员密码写入命令行、脚本、Git、Nacos 或聊天记录。

脚本是幂等的：已存在的数据库和账号不会默认改密码；只有显式设置 `RIGOUR_DB_ROTATE_EXISTING_PASSWORDS=true` 才会按凭据文件轮换已有账号密码。脚本执行后会逐个用运行账号和迁移账号登录测试，并报告当前表数；新建库期望表数为 `0`。

## 连接参数约定

后续某个领域真正接入 JDBC/Flyway 时，Nacos 只保存以下非敏感参数和环境变量引用，不在本仓库写真实密码：

各服务对应的 Nacos Data ID 模板见 [`docs/nacos/README.md`](./nacos/README.md)。

```yaml
spring:
  datasource:
    url: jdbc:mysql://82.157.4.176:13306/<schema>?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true
    username: <service_app_user>
    password: ${<SERVICE>_DB_APP_PASSWORD}
  flyway:
    url: jdbc:mysql://82.157.4.176:13306/<schema>?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true
    user: <service_migrator_user>
    password: ${<SERVICE>_DB_MIGRATOR_PASSWORD}
    baseline-on-migrate: false
    validate-on-migrate: true
    clean-disabled: true
```

Sales Work 已加入 JDBC/Flyway 依赖和正式 V1 迁移，但尚未在共享 DEV 执行；其余未落地领域仍不能仅凭创建空库就宣称服务已接入数据库或可用。

## 密码查看与轮换

真实密码只保存在服务器的 `/opt/rigour-dev/domain-db.env`，文件权限为 `0600`。负责人如需在可信终端查看，直接 SSH 到服务器后执行：

```bash
sudo sed -n '1,80p' /opt/rigour-dev/domain-db.env
```

不要截图、复制到聊天、写进 Nacos 或提交 Git。密码轮换后，必须同步更新运行服务的本机 Secret/IDEA 环境变量，并逐个重新执行账号登录验证；不能用 `root` 运行领域应用。
