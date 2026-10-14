# 销售工作台 App 原生登录与 IAM 接入

更新：2026-10-14。App 使用自己的原生账号密码表单；IAM 统一负责密码校验、失败锁定、登录会话、Token 签发与轮换、权限和会话撤销。不是新建账号库，也不使用 OAuth password grant。Web 登录和生产临时打卡保持原有路径。

## 调用合同

1. App 使用安全随机数生成独立 verifier/state 和 S256 challenge。
2. `POST /api/v1/auth/native-app/login`（IAM origin，JSON）提交 `clientId, redirectUri, codeChallenge, codeChallengeMethod: S256, state, username, password`，可附 `tenantCode, deviceName`。省略 tenantCode 时使用 IAM 已有默认租户。
3. IAM 只允许明确配置的独立 Sales App PUBLIC 客户端，复用密码认证提供器和标准授权码提供器；响应 `ApiResponse.data` 包含 `authorizationCode, expiresIn, state`。密码错误、账号不可用统一返回认证失败，不创建浏览器 Cookie 会话。
4. App 校验 state 后，通过现有 `/oauth2/token` 以 `authorization_code + code_verifier + client_id + redirect_uri` 交换令牌，随后请求可信 Gateway `/api/v1/me` 验证身份与平台权限。
5. 过期续期仍调用 `/oauth2/token` 的 `refresh_token` 授权。IAM 轮换旧凭据、检测重放；App 必须单飞刷新、避免旧响应覆盖新账号或重复消费旧 refresh。
6. `POST /api/v1/auth/native-app/logout` 使用 Bearer Access Token，只撤销该 Token 对应的原生 App 会话、授权及 refresh 链，成功返回 204；不接受调用方传入其他 sessionId，不退出其他 Web/App 会话。

`redirectUri` 继续作为标准授权码交换的精确客户端绑定值，但新流程不通过浏览器或系统 scheme 返回 App。原有浏览器 scheme 回调验收不能当作原生表单验收。

原生入口默认关闭。启用时配置：

```properties
rigour.iam.authentication.native-app.enabled=true
rigour.iam.authentication.native-app.allowed-client-ids=rigour-sales-app-android-dev
```

PUBLIC 客户端仍需先注册。开发环境首次注册使用：

```properties
rigour.iam.bootstrap.sales-app-client.enabled=true
rigour.iam.bootstrap.sales-app-client.client-id=rigour-sales-app-android-dev
rigour.iam.bootstrap.sales-app-client.client-name=瑞盖销售工作台 Android DEV
rigour.iam.bootstrap.sales-app-client.redirect-uri=uniappx://rigour-sales/auth/callback
rigour.iam.bootstrap.sales-app-client.allow-debug-base=true
```

注册仅允许 dev/local 的显式调试配置，含 prod profile 时拒绝；已有配置漂移会报错而非覆盖。注册后关闭 bootstrap 开关，原生登录开关独立保留。正式环境使用各平台独立正式客户端及 HTTPS，不沿用 DEV 客户端。

## 会话与令牌

- 客户端认证 `none`，没有客户端 Secret；授权 `authorization_code`、`refresh_token`；强制 S256 PKCE、固定 `profile` scope、不签发或使用 ID Token。
- 授权码 5 分钟、Access Token 15 分钟、轮换 Refresh Token 7 天；refresh 仍受 IAM 登录会话绝对期限约束。当前密码会话默认 8 小时，7 天 refresh TTL 不等于承诺 7 天免登录。本轮不修改平台会话期限。
- 新原生会话使用 `NATIVE_APP`，V133 仅扩展已有客户端类型约束；保留 WEB、FEISHU_H5 和现有会话。不改写已执行的迁移。
- Android 使用 Keystore 加密保存 refresh 和最小账号/环境绑定信息；Access Token 仅在内存，密码不落盘。iOS/HarmonyOS 安全存储及真实认证需各自接入验证。
- 刷新发送前作废旧的持久凭据，成功收到并安全保存新凭据后才发布新登录态。超时或响应丢失时不盲目重放旧 refresh；重新登录并保留业务草稿。
- App 清本机凭据和 IAM 撤销是两个动作。断网时不能声称服务端已撤销，不能删除未上传的拜访草稿来代替退出。

## 本机隔离 DEV

App Gateway `http://localhost:27680`、IAM `http://localhost:27681`；Android 经 ADB reverse 访问。只有明确标为 development 的精确 loopback 配置允许 HTTP，正式环境必须 HTTPS。独立进程关闭 Nacos 注册，保留已运行 IDEA 服务。

Gateway 的 issuer、JWKS、current-token 校验和 `/me` 路由均指向隔离 IAM，保持签名和当前会话校验；业务接口继续使用 DEV 的真实数据与权限。App 自有登录页不改变客户、订单或 Sales 的数据范围。

## 迁移与验收状态

本轮原生表单改动的验证结果以实际测试和手机回执为准。此前已验证 Android 浏览器登录、管理员客户列表/详情和订单列表，不能直接作为本次原生登录、续期或重新启动恢复的验收。

2026-10-14 16:05 已将同事推送的远程 dev `b7d0f96d` 合并到 App 接入分支，合并提交 `87a3c819`，无冲突。V132 原始文件已补齐，Flyway 校验值 `-176225895` 与共享 DEV 历史匹配；只读校验不再报告缺失或校验值冲突，仅报告 V133 尚未执行。本次拉取合并未重启服务、未执行共享 DEV 迁移，也未改写任何历史 SQL。

合并验证：

- `prepare-general-ci-baselines.py --check` 通过：9 个冻结基线、340 个历史迁移保持原样。
- IAM 原生密码、PKCE、refresh 轮换/重放、账号停用、锁定、退出及 OIDC 配置共 47 项定向测试通过，0 跳过；在一次性 Testcontainers 中显式采用 `-Dspring.flyway.locations=classpath:db/migration`，验证与现有 DEV 相同的历史升级链路，日志 `/tmp/iam-native-after-dev-merge-existing-schema-tests.log`。此参数仅用于该次测试，没有改动服务配置或关闭 Flyway。
- 默认空库路径另有已复现的问题：新增 B118 的 `utf8mb4_general_ci` 字符列，与既有 V120 临时表明确指定的 `utf8mb4_0900_ai_ci` 在 JOIN 比较时冲突，导致测试上下文不能启动。日志 `/tmp/iam-native-after-dev-merge-tests.log`。不能将上述 47 项通过解释为默认空库初始化已通过，也不能改写已发布的 B118/V120 来消除校验。该问题需独立修复。
- 共享 DEV 的只读校验日志：`/tmp/iam-native-dev-flyway-after-merge.log`。原生入口启用、V133 实际升级及手机登录验收仍待执行。

普通销售验收仍需真实员工身份、Sales 画像、门店分配及有效规则；不能以管理员或临时打卡身份代替。
