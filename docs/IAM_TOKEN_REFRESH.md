# SCDP 登录与自动续期

更新日期：2026-09-16。适用范围：IAM 与供应链 Web；不修改网关业务鉴权、不新增环境配置。

## 登录流程

1. 登录表单通过同源代理建立 IAM HttpOnly 会话，保留 CSRF 校验。
2. Authorization Code + PKCE 换取 Access Token、Refresh Token 和用于验证身份的 ID Token。
3. Access Token 保持原有 15 分钟有效期。API 请求或受保护路由进入时，距到期不足 5 秒即刷新；休眠标签页恢复后同样适用。
4. 使用标准 `POST /auth/oauth2/token`、`grant_type=refresh_token`、`client_id` 和当前 Refresh Token 续期，不需要前端客户端密钥。
5. 刷新后替换两种 Token；多个请求共用同一次刷新，迟到的旧 Token 401 不重复轮换。明确鉴权失败的原请求最多重试一次，保持当前页面及查询条件。

普通业务 401、无权限 403、服务 503 不等同于登录过期。网络或续期服务故障保留会话；刷新令牌失效、会话撤销或新 Access Token 仍被拒绝时返回登录页。

## 会话边界

- Access/Refresh Token 只保存在当前页面内存，不落 Web Storage。整页刷新通过既有 HttpOnly 会话重新授权，这是仍有用途的恢复流程，不是运行时续期。
- Refresh Token 强制轮换；IAM 只保存摘要，检测到旧令牌重放即撤销会话。原登录的 sid/auth_time 以加密属性保留，不保存可用的 ID Token 原文。
- 原有 IAM 会话最大期限保留，当前默认 8 小时。续期不是永久登录，也不通过空闲心跳无限延长会话。
- 退出成功后清空令牌；在途刷新和旧会话迟到的 401 不能恢复或清理新登录会话。
- 单次刷新超时 10 秒且不内部重试。网络恢复后可再次尝试；若服务端已经轮换但响应丢失，旧令牌会失效，需要重新登录。

Spring 默认不向公开客户端签发 Refresh Token，因此 IAM 使用标准扩展点补充公开 PKCE 客户端的刷新识别和签发；只允许已登记 authorization_code + refresh_token、强制 PKCE 且禁止复用刷新令牌的客户端。机密客户端继续使用框架默认签发器。协议及安全依据：[Spring PKCE 指南](https://docs.spring.io/spring-authorization-server/reference/guides/how-to-pkce.html)、[RFC 9700 4.14.2](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14.2)。

## 本机启用

1. 在 IDEA 重新构建并启动 `services/rg-scdp-iam/iam-server` 的 `IamApplication`，继续使用现有 dev profile。
2. 确认 Flyway 成功执行新增 V109：为现有 SCDP 公开 PKCE 客户端补充 refresh_token 授权。旧迁移文件未改写。不要仅凭进程启动就认定迁移成功。
3. 刷新 `http://localhost:5100` 并重新登录一次。旧页面中的单 Token 不能凭空获得 Refresh Token。
4. 正常操作跨过 15 分钟，浏览器 Network 应看到 token 端点的 refresh_token 请求成功，随后业务接口继续成功，页面地址不变。不要截图或日志输出完整令牌。

共享 DEV 部署顺序同样是先更新 IAM 并完成迁移，再发布 Web。无需新增 Nacos 配置、客户端密钥或定时任务。

## 验证与交付边界

- 前端全量测试：79 个文件、566 项通过；类型检查、涉及文件的 lint、构建通过。
- 后端定向测试：14 项通过、0 跳过，包含真实隔离 MySQL 上的换码、刷新、轮换、重放撤销、登出失效、客户端与 scope 绑定、过期校验及 V109 有数据升级和幂等验证。
- 扩大 IAM 回归时，`IamApplicationTests.supplyMenuDeletionRevokesGrantAndRecreationDoesNotRestoreIt` 在第 1479 行失败；单独重跑仍失败。它检查菜单重建后的权限，本次未修改该业务逻辑，不能据此宣称后端全量通过。
- 本次清理了到期直接清空令牌的旧分支，统一续期入口；保留有实际调用方的 Token 读取封装和整页会话恢复。未增加 iframe、后台心跳或额外配置文件。
- 验证运行于本机及隔离测试数据库；未重启当前 IDEA 调试进程，未执行共享 DEV 部署，尚未对运行中的本机门户做真实 15 分钟跨期验收。本次修改未提交或推送。
