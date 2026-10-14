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

2026-10-14 已合并同事推送的 dev `b7d0f96d`（合并提交 `87a3c819`）。V132 原始文件与 DEV 校验值 `-176225895` 一致。保留全部已发布 B/V 文件；通过独立的新 B120 修复空库 general-ci 与 V120 临时表、隐式表默认值、权限视图常量的排序规则冲突。

本次验证：

- 冻结基线校验通过：10 个基线记录覆盖 9 个服务，原文件的字节与校验值未变。
- 默认 Flyway 双目录配置下 IAM 49 项、排序规则策略 3 项，共 52 项通过，0 跳过；未使用迁移目录覆盖。日志 `/tmp/iam-app-baseline-fixed-tests.log`。覆盖空库 B120→V133、已有 V132 库仅升级 V133、数据及历史校验保留、重复启动无待执行迁移，以及密码、PKCE、refresh、退出和 Web 回归。
- 16:28 本机隔离 IAM `127.0.0.1:27681` 启动新版，仅执行 V133；共享 DEV 的 V132/V133 均成功，原 V132 校验不变，SQL_BASELINE 记录数为 0。V133 校验值 `-1799016397`；会话约束包含 WEB、FEISHU_H5、NATIVE_APP。升级后只读 Flyway validate 成功，日志 `/tmp/iam-native-dev-flyway-after-enable.log`。
- 原生入口仅在该隔离进程显式启用，允许 `rigour-sales-app-android-dev`；已有客户端 bootstrap 已关闭。关闭 Nacos 注册，保留 IDEA 和其他服务。
- 真实 DEV 管理员接口验收：原生密码登录 200、PKCE 换取令牌 200、Gateway `/me` 200、客户/订单只读列表 200、refresh 200 且旧新凭据不同、续期后 `/me` 200、原生退出 204、退出后 refresh 400 invalid_grant。验收创建的独立会话已撤销，未改账号密码、权限或业务数据；未记录 Token/密码。
- Android 16:24:20 编译成功、16:24:26 文件同步成功、16:24:28 真机启动并进入 `pages/login/login`，日志 `/tmp/sales-app-native-login-android-sync-20261014.log`。用户在后端启用前尝试登录收到旧服务的 401；启用后 16:30:11 手机原生登录 200、16:30:12 令牌交换及 `/me` 200，用户明确确认“登录成功，重开也能恢复”。安卓原生登录与重开恢复通过真机验收；不扩大为其他平台或普通销售权限验收。

范围：开发环境和本机安卓调试包；未部署生产、未切换或停用临时打卡系统。普通销售验收仍需真实员工身份、Sales 画像、门店分配及有效规则，不能以管理员或临时打卡身份代替。

## 2026-10-14 数据等待优化

- IAM 的只读授权计算在一次调用内复用 applicationId、初始化状态、账号资格、菜单、订阅目录和管理员角色；键含 Actor，嵌套调用共享，正常/异常返回后均移除。没有跨请求权限缓存，session/securityVersion/policyVersion 仍逐请求检查；配置写入路径不启用该复用范围。
- 25 项定向测试通过（0 跳过），包括真实 MySQL 的权限撤销、账号隔离、并发单连接池检查及作用域异常清理、原生登录回归。`/tmp/iam-authorization-perf-tests-final.log`。测试的 JdbcTemplate 读方法调用数由 52 降到 21；包含重载方法调用，不代表数据库 SQL 条数。
- App 专用 CRM `127.0.0.1:27683`、Order `127.0.0.1:27685` 关闭 Nacos 注册，在线授权指向 App IAM `27681`；Gateway `27680` 的这两个路由切至上述隔离进程。IDEA 的 268xx 服务不变。各服务仍使用 DEV 原数据库；启动前只读 Flyway 校验通过，无待迁移数据库变更，未停用 Flyway。启动脚本 `/tmp/sales-app-start-dev.py`，运行日志 `/tmp/rigour-sales-app-dev/`。
- 停止本任务暂未使用的 iPhone 15 Pro iOS 17 模拟器以减少本机内存压力；没有清除模拟器数据。测试机为 16GB 内存，诊断时交换空间占用约 14.8GB。编译/容器启动期间的 3–5 秒请求不用于修复效果比较。
- 相同隔离拓扑、真实 DEV 管理员、每页 20 条、每接口 5 次请求，IAM 优化前/后中位数（毫秒）：current-token 246.6 → 166.4，客户列表 900.7 → 503.7，订单列表 1053.9 → 740.0。分别见 `/tmp/app-data-perf-before.txt`、`/tmp/app-data-perf-after.txt`；这是本机接口样本，不是手机点击到页面完成的耗时或生产 SLA。返回总数客户 2389、订单 3567 前后一致。
- 手机随后实测仍约 845ms/1166ms 等接口，用户反馈无明显体感变化。因此 App 再增加首页预加载，进入客户/订单共用已有成功结果及进行中的请求；不把上述接口均值当作真机验收完成。
- 后端修复后真实 DEV 客户/订单详情 ID 一致、刷新令牌轮换、`/me` 成功；测试会话退出后 current-token 返回 401。测试只读业务数据，撤销的仅是本次测试会话。生产临时打卡系统未改动。
