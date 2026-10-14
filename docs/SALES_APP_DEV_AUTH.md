# 销售工作台 App DEV 公司登录

本方案新增独立 PUBLIC OAuth 客户端，复用供应链 IAM 的员工账号、权限和 Gateway，不修改临时打卡，也不覆盖浏览器客户端、用户或角色授权。注册默认关闭，不需要修改 Flyway 历史或手工写数据库。

## Android 标准基座

本机 HBuilderX 5.26 Android 标准基座 Manifest 的包名为 `io.dcloud.uniappx`，浏览器回调 scheme 为 `uniappx`。DEV 配置使用精确回调 `uniappx://rigour-sales/auth/callback`；共享 scheme 仅用于受控开发手机，正式发布不能沿用。

本机 IAM 使用 `dev` 或 `local` profile（同时含 `prod` 时拒绝调试回调），启动时显式启用：

```properties
rigour.iam.bootstrap.sales-app-client.enabled=true
rigour.iam.bootstrap.sales-app-client.client-id=rigour-sales-app-android-dev
rigour.iam.bootstrap.sales-app-client.client-name=瑞盖销售工作台 Android DEV
rigour.iam.bootstrap.sales-app-client.redirect-uri=uniappx://rigour-sales/auth/callback
rigour.iam.bootstrap.sales-app-client.allow-debug-base=true
```

使用 IAM 已有 `RegisteredClientRepository` 创建客户端。再次启动仅核对现有安全配置，发现差异时拒绝覆盖。完成后可关闭 bootstrap 开关。源码没有现成 OAuth 客户端注册管理 API，供应链“应用设置”不是 OAuth 客户端管理；本轮没有新增管理 API。

本轮独立进程使用 IAM `27681`、Gateway `27680`，保留已运行的 IDEA 服务。手机通过 `adb reverse tcp:27681 tcp:27681`、`adb reverse tcp:27680 tcp:27680` 使用本机服务，issuer 为 `http://localhost:27681`。App 中仅明确标为 DEV 的 loopback 地址允许 HTTP；不能把这项例外扩大为局域网地址、任意域名或正式环境 HTTP。还须现场核对 IAM discovery 的 issuer 与 App 配置精确一致，以及 Gateway 的 issuer 校验配置一致。

独立进程不注册共享 Nacos，使用隔离配置将 Gateway 的 `/me`/IAM 路由、`jwk-set-uri`、`iam-current-token-uri` 显式指向本机 `27681`，不能让新 issuer 的令牌绕到旧 IAM 再验签。Gateway 启用 `allow-insecure-loopback`，保留 `allow-insecure-http=false` 和 `current-token-validation-enabled=true`；原有共享配置、签名校验与业务数据权限不关闭。

系统浏览器直接访问 IAM `/oauth2/authorize`，由 IAM `/login` 表单处理密码及 CSRF，并恢复原授权请求。不要走供应链 `/auth` 中转到 Web `/#/login` 或 `/scdp/login`；该流程服务于 Web 会话，并清除保存的授权请求。

## OAuth 合同

- 独立客户端 ID 只接受 `rigour-sales-app-android`、`rigour-sales-app-ios`、`rigour-sales-app-harmony`，以及各自 `-dev` 形式。
- 客户端认证方式 `none`，不保存 Secret；授权类型 `authorization_code` 与 `refresh_token`；强制 PKCE。App 使用独立安全随机 verifier/state 和 `S256`。
- 仅注册 `profile` scope，App 不申请、不解码、不使用 ID Token。Access Token 通过可信 Gateway `/api/v1/me` 再次验证员工身份，功能权限和数据范围仍由服务端判定。
- 授权码 5 分钟、Access Token 15 分钟、轮换 Refresh Token 7 天。刷新有效性仍受 IAM 登录会话期限限制，7 天不等于承诺 7 天免登录。客户端未实现安全存储和串行轮换前，不得持久化 Refresh Token。
- 回调要求精确匹配 scheme/host/path，无可变 query/fragment/端口/用户信息；逐次 state 校验，超时与重复回调拒绝。客户端注册本身不授予业务权限。

现有 PUBLIC refresh 实现要求 PKCE、code+refresh 和禁止复用；数据库仅留 refresh 摘要，轮换旧值并检测重放，重放会撤销关联会话。App 刷新需单飞，不能在网络失败后盲目重复使用旧 refresh。平台已有会话退出和当前会话验证，App 清内存退出不等同于全局退出。

## 正式包与其他端

正式客户端使用 `com.rigour.workbench://auth/callback`，`allow-debug-base=false`，各平台单独客户端 ID。Android 配置公司包的 `AndroidManifest.xml`，iOS 配置 URL Schemes，HarmonyOS 配置 `module.json5`/HBuilderX 对应 scheme 项，并分别验证冷启动和前台回调。配置必须进入重打包的基座或签名包，热更新源码不会更改已安装基座的系统回调注册。

- [DCloud Android scheme 配置](https://doc.dcloud.net.cn/uni-app-x/collocation/app-nativeresource-android.html)
- [DCloud iOS URL Schemes](https://doc.dcloud.net.cn/uni-app-x/collocation/manifest-ios.html)
- [DCloud App 生命周期与鸿蒙 scheme](https://doc.dcloud.net.cn/uni-app-x/collocation/app.html)

生产 TLS/issuer 配置属于单独发布步骤，不应为接通本轮 DEV 修改现有生产 issuer。2026-10-14 只读观察到现有供应链公开入口仍为 HTTP，代理 discovery 的 issuer 是回环 HTTP；该观察不是移动端生产可用证明。

## 必要验收

本机编译与定向测试后，需要真实 DEV 员工执行浏览器登录→回调→`/me`，确认同一用户 ID、tenantId 和实时权限。验证错误 PKCE、未注册回调、缺权限 403、过期 401、禁用账号/角色变更生效、账号切换不残留上一账号数据。Sales 还要求员工身份投影、有效销售画像、拜访目标分配及生效规则；不能通过增授管理员权限绕过这些前置。

定向验证命令：

```shell
./mvnw -pl services/rg-scdp-iam/iam-server -am \
  -Dtest=SalesAppClientBootstrapTest,PublicRefreshClientAuthenticationTest,IamAuthorizationServerSecurityTests#nativeProfileOnlyClientCompletesLoginPkceAndVerifiedIdentity \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

其中原生登录集成用例通过独立 Testcontainers MySQL 注册客户端、使用 IAM 登录表单及 CSRF 完成 PKCE 授权、交换 profile-only 令牌并调用 `/me`，验证无 ID Token 且非法回调被拒绝；不接触 DEV 数据库。测试不证明真实 DEV 数据库已经有客户端或手机登录已验收。
