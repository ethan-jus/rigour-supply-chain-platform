# IAM数据库运行时接入

## 1. 当前边界

- 逻辑Schema：`rigour_iam`。
- 开发MySQL：`82.157.4.176:13306`。
- V1～V6迁移由`services/rigour-tenant-iam-service/iam-service`的Flyway执行。
- 数据源地址和非敏感参数放在Nacos；数据库密码只通过环境变量或部署平台Secret注入。
- 真实密码不进入Git，也不能使用MySQL root账号启动服务。

## 2. 账号权限

开发服务器已创建以下账号：

| 账号 | 权限 |
|---|---|
| `rigour_iam_migrator` | 仅`rigour_iam.*`所需DDL、索引、外键和迁移DML权限 |
| `rigour_iam_app` | 仅`rigour_iam.*`的`SELECT/INSERT/UPDATE/DELETE`；无DDL、无跨Schema权限 |

开发环境两个账号当前使用相同的开发密码，但权限仍按账号拆分。密码值复用`/opt/rigour-dev/.env`中的`MYSQL_PASSWORD`，不写入Git或Nacos；该约定只适用于开发环境，生产环境必须为运行账号和迁移账号分别生成密码。

为支持团队成员从允许的公网IP连接，开发账号当前使用`'%'`主机范围，公网入口继续由云防火墙白名单限制。生产环境必须收紧为实际应用来源。

开发库已在2026-07-30由Flyway执行V1～V6，当前版本为6，包含22张IAM业务表；没有使用`baseline`，也没有手工伪造`flyway_schema_history`。

## 3. Nacos配置

1. 打开[Nacos配置模板](nacos/rigour-tenant-iam-service.example.yml)。
2. 将内容复制到开发Namespace `dev`（ID `3aa03547-8948-4254-bd94-47c630db128b`）下的`rigour-tenant-iam-service.yaml`。
3. 保留`${IAM_DB_APP_PASSWORD}`和`${IAM_DB_MIGRATOR_PASSWORD}`引用，不要替换成明文。
4. 在本地IDE/终端或部署平台中注入四个环境变量：

```text
IAM_DB_APP_USERNAME=rigour_iam_app
IAM_DB_APP_PASSWORD=<真实运行密码>
IAM_DB_MIGRATOR_USERNAME=rigour_iam_migrator
IAM_DB_MIGRATOR_PASSWORD=<真实迁移密码>
```

5. 发布Nacos配置后启动IAM服务，Flyway会先校验并执行尚未应用的迁移。

`${IAM_DB_APP_PASSWORD}`和`${IAM_DB_MIGRATOR_PASSWORD}`不会在Nacos服务器上解析。Nacos把占位符原样下发，最终由运行Spring Boot的本地电脑或部署容器从自身环境变量解析。

用户名不是敏感信息，模板提供了默认值；密码必须显式提供且没有默认值。普通`.env`文件不会被Spring Boot自动读取，本地启动时应在IDE运行配置或当前终端中设置；部署时使用权限受限的环境文件、Docker/Kubernetes Secret或同等密钥设施。

开发负责人可在可信终端查看当前共用开发密码：

```bash
ssh -i /Users/ethan/.ssh/rigour_dev_server_ed25519 ubuntu@82.157.4.176 \
  "sudo sed -n 's/^MYSQL_PASSWORD=//p' /opt/rigour-dev/.env"
```

该命令会在终端显示密码，不要截图、粘贴到聊天或写入项目文件。IDEA运行配置中的`IAM_DB_APP_PASSWORD`和`IAM_DB_MIGRATOR_PASSWORD`在当前开发环境填写相同值。

所有服务的`application-dev.yml`默认使用Namespace ID `3aa03547-8948-4254-bd94-47c630db128b`，仍可通过`NACOS_NAMESPACE`覆盖。`dev`只是控制台显示名称，不能作为客户端Namespace参数。

首次接入共享开发库前必须备份并确认`flyway_schema_history`为空或不存在；不得使用`baselineOnMigrate=true`、`repair`或手工删除历史记录掩盖迁移冲突。

## 4. 验证

启动后至少核对：

```sql
SELECT version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;

SELECT COUNT(*) AS table_count
FROM information_schema.tables
WHERE table_schema = 'rigour_iam';
```

期望V1～V6全部成功、业务表22张。Flyway历史表不计入22张业务表。
