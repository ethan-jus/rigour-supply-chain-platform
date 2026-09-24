# 公共接口响应与异常处理规范

## 方案依据与取舍

参考 Spring Framework 7 的 [Error Responses](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)、[ResponseBodyAdvice](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/ResponseBodyAdvice.html)、[Spring Security 异常边界](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/web/access/ExceptionTranslationFilter.html)、[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html) 及 [Axios 拦截器](https://github.com/axios/axios-docs/blob/master/posts/en/interceptors.md)。

采用公共异常转换、真实 HTTP 状态、安全业务原因、可追踪请求 ID 和集中客户端处理。保留现有 `ApiResponse` 契约，不将本次实现宣称为 RFC 9457 的 `application/problem+json`：改换已有协议会同时影响 Portal、移动端和服务间客户端。

## 后端职责

`shared-core` 自动配置注册 `GlobalExceptionHandler` 和 `ApiEnvelopeAdvice`，所有通过平台 starter 引入 core 的 MVC 服务复用。控制器无需逐个 try/catch、手工重复错误格式。

成功 JSON 响应的外壳：`code=OK, message=success, data, requestId, timestamp`。
失败响应：`code, message, details, requestId, timestamp`；空字段遵循现有 NON_NULL 约定。

成功响应按 `Accept: application/vnd.rigour.api+json` 协商，Portal 公共 Axios 客户端统一发送，解包后页面仍收到原 DTO。旧客户端不发送该类型时仍收到原 DTO。响应设置 `Vary: Accept`，避免不同表示混用缓存。需要统一接口的新增客户端也应发送此 Accept。

适用范围为 `/api/v1/` 下的 JSON DTO；已封装的 ApiResponse 不重复包装。数组、分页结构原样放入 data，数字/金额不转换。文件、字节、Resource、文本、204/205、非成功状态不强制包装；OAuth/OIDC、内部接口和标准 ProblemDetail 各自维持原协议。流式响应不经过普通 DTO 包装。

异常转换：

| 情形 | HTTP | 公共错误码/行为 |
|---|---:|---|
| 明确的业务输入校验 | 400 | VALIDATION_FAILED，安全中文原因 |
| JSON 无法解析、参数类型/缺失 | 400 | BAD_REQUEST，不输出底层解析细节 |
| 无权限 | 403 | IAM_FORBIDDEN / FORBIDDEN |
| 未找到 | 404 | NOT_FOUND |
| 请求方法不支持 | 405 | METHOD_NOT_ALLOWED，保留 Allow |
| 数据/版本冲突、重复唯一键 | 409 | CONFLICT，不输出 SQL |
| 请求过大 | 413 | PAYLOAD_TOO_LARGE |
| 内容类型不支持 | 415 | UNSUPPORTED_MEDIA_TYPE |
| 频率限制 | 429 | RATE_LIMITED，框架响应头保留 |
| 下游连接不可用 | 503 | SERVICE_UNAVAILABLE |
| 未预期程序异常 | 500 | INTERNAL_ERROR；堆栈仅服务端日志 |

框架异常继承 `ResponseEntityExceptionHandler` 统一处理，保留其 HTTP 状态和响应头，不把 405/415 等误判为 500。

业务代码使用 `BusinessException` 或纯 Java 的 `RequestValidationException` / `StateConflictException`；这两个类型必须只携带可公开的原因。禁止以统一捕获所有 IllegalArgumentException / IllegalStateException 的方式公开底层实现。此次已迁移 IAM 设置域的菜单、角色、用户、范围规则与切换校验，并删除其独立异常 Advice。其他服务现有 BusinessException 继续生效；其他遗留业务代码若仍以普通运行时异常表示用户校验，需要在对应业务修复时明确分类，不能批量把程序错误改成 400。

安全过滤器位于 MVC 外，不能依靠 ControllerAdvice 捕获。Gateway 已有 EntryPoint / AccessDeniedHandler 并输出 ApiResponse；可信上下文过滤器保留 `X-Rigour-Auth-Failure` 稳定标识及其旧响应协议。前端同时支持响应体与该响应头，避免将服务身份校验故障误判成用户需要退出登录。OAuth 登录失败/重定向及 token 端点遵循其协议，不强行修改。

## 前端职责

`src/api/core/client.ts` 负责认证头、追踪头、成功解包、最终失败归一化；`ApiError` 统一承载 Error.message、HTTP status、code、details、requestId、timestamp。兼容已有读取 `response.status` 的守卫，但错误对象不携带完整 Axios config、Authorization 或原始 HTML。

- 400/403/404/409/413/415/429/500/502/503/504 有安全中文兜底。
- 无响应与请求超时分别分类；HTTP 错误不再伪装成网络失败。
- 下载失败且返回 JSON Blob 时解析业务原因；正常 Blob 原样返回。
- 只有明确 Token 失效或 IAM 会话复核确认失效才恢复登录；业务 401、403 不随意清登录态。认证刷新仍最多重试一次。
- 内部会话复核不单独提示；最终失败提示保留，重试成功不产生错误通知。
- 取消请求静默；同一异常对象多层传播只提示一次，同类错误合并。
- 公共通知位于弹窗上方，显示请求 ID，可关闭。业务表单保留原有字段校验与就地错误，用户输入不因请求失败被整页兜底替换。

新增页面统一从 apiClient 发请求，不自己实现拦截器或按 401 一律退出。catch 只负责页面状态恢复和业务补救；公共提示不会吞掉拒绝结果。写操作不因通用 5xx/超时自动重发。

## 验证及上线

本轮新增共享 MockMvc 协议测试和前端错误矩阵测试；覆盖协商/旧调用兼容、分页、文件、空响应、不重复包装、解析/方法/格式错误、安全消息、HTTP 状态、JSON Blob、取消以及登录恢复。详细执行结果记录在工作区 `outputs/iam-permission-check-20260923/公共异常处理优化验收.md`。

此为前后端代码变更，无数据库迁移，不会自动启用角色新数据范围。部署需更新公共库及使用它的服务、发布前端。IDEA 运行实例需重新构建/启动加载；仅刷新页面不能使后端公共类生效。开发联调与上线验收应分别记录，测试通过不等于当前运行实例已更新。
