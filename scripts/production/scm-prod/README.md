# SCM 生产部署

9 个服务：Gateway、IAM、Integration、CRM、ERP、Order、BI、HR、Foundation；不启动 Sales、AI。

- JAR：`/root/rg_scdp/<service>-server.jar`，JDK：`/usr/local/jdk21`。
- 前端：`/usr/local/nginx/rgscdp_dist`，入口：`http://8.140.247.79:2026`。
- 备份 `/usr/local/nginx/conf/nginx.conf` 后，在该文件的 `http` 段内直接追加 2026 站点；`nginx -t` 通过才平滑加载，保留原站点。
- Nginx `/auth/` 转发 IAM 26881，`/api/` 转发 Gateway 26880；Gateway 按功能与 Nacos 服务名转发，保留认证和上下文签名。
- `NACOS_NAMESPACE` 必须填写 `scm-prod` 的真实 ID。共享 Data ID 为 `rigour-common.yml`，服务配置为 `<spring.application.name>.yml`，Group 为 `DEFAULT_GROUP`。禁止填入敏感值。
- 新数据库独立监听 `127.0.0.1:13306`，使用 `/opt/rg-scdp-mysql` 和 `/var/lib/rg-scdp-mysql`。现有 MySQL 3306 不变。先验证 DEV 8.4 SQL 在新 MySQL 8.0 上全量导入成功，再启动应用。
- 新 Redis 独立监听 `127.0.0.1:16379`，数据目录 `/var/lib/rg-scdp-redis`；不共用原 Redis 数据。
- 8 个业务库完整导入：`rigour_iam`、`rigour_settings`、`rigour_hr`、`rigour_crm`、`rigour_erp`、`rigour_order`、`rigour_integration`、`rigour_bi`。保留 Flyway 历史，生产关闭自动迁移。

`/etc/rg-scdp/common.env`、`nacos.env`、`<service>.env` 权限为 600，目录为 700，由 systemd 注入环境变量。密码和 COS AK 不进入 Git、JAR、Nacos 或日志。禁用旧的 local-secrets 属性注入，避免覆盖环境变量。复制 IAM 签名文件到新服务 `user.home` 下的相同相对路径，权限 600，不覆盖旧服务私钥。

| 服务 | 端口 | Xms / Xmx |
| --- | --- | --- |
| Gateway | 26880 | 128m / 192m |
| IAM | 26881 | 128m / 256m |
| Integration | 26882 | 128m / 256m |
| CRM | 26883 | 128m / 192m |
| ERP | 26884 | 128m / 256m |
| Order | 26885 | 128m / 256m |
| BI | 26888 | 128m / 256m |
| HR | 26889 | 128m / 192m |
| Foundation | 26892 | 128m / 128m |

总最大堆 1984 MiB；JVM 的实际 RSS 还包含元空间、线程栈、直接内存等。逐个启动并检查健康与服务器剩余内存，不能将 Xmx 视为总内存占用。

HTTP 部署显式注入 `SESSION_COOKIE_SECURE=false`、`OIDC_ALLOW_HTTP_LOOPBACK=true`、`OIDC_ALLOW_HTTP_ORIGIN=true`、`GATEWAY_ALLOW_INTERNAL_HTTP=true`；`OIDC_ISSUER=http://127.0.0.1:26881`，`SCDP_ORIGIN=http://8.140.247.79:2026`。前端使用仓库 `deploy/linux-http/build.sh`，登录请求经过同源 `/auth/` 代理。通用 prod 配置仍默认要求安全 Cookie 和 HTTPS。

生产 Maven 构建使用 `-Pscm-prod clean package`，该 profile 排除 `application-dev.yml` 和 `application-local.yml`，避免将开发连接凭据打入生产 JAR。

顺序：检查 prod 配置及环境变量 → 配置加载测试 → 打包 → SQL 导入与行数检查 → 目标库登录回调适配 → 单服务健康检查 → Nginx 配置校验与加载 → 原站点及新站点验收。

导入后的 IAM 视图 `iam_effective_tenant_role_resource` 原定义引用 DEV 的 `rigour_iam_migrator@%`，新实例不存在该账号。已先保存原 CREATE VIEW，再将同一查询改为 SQL SECURITY INVOKER，由当前 IAM 应用账号依其自身权限读取；业务表、数据与历史迁移均未改写。不能只用 SELECT 1 或健康检查代替实际授权查询。

小内存部署下并发页面请求曾触发 Gateway 到 IAM 的 3 秒会话校验超时；scm-prod 的 `rigour-api-gateway.yml` 将 connect timeout 设为 3s、read timeout 设为 10s，继续保留在线会话和权限校验。
