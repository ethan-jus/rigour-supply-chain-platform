# 后台刷新与 Nginx 限流回归

2026-09-08，使用真实 Nginx 1.18.0、Playwright 驱动本机 Chrome，全部请求仅进入本机合成上游。未启动 Java，未使用账号、客户数据或对象存储。

## 已验证结果

- 旧 `db8bc09` 模板：正常加载一屏 20 图再刷新，共 27 次响应，其中 18 次为 503，复现资源被后台共用额度拦截。
- 新模板：4 轮进入、刷新、进入详情、浏览器返回，共 416 次响应全部 200；包含 320 次图片请求，CSS、JS、统计各 16 次。浏览器实际解码图片、应用样式并完成统计读取。
- 读请求、媒体、写请求过载均返回 429；并发上限分别为 20、20、5。占满业务并发时 CSS/JS 仍可读取。
- 100 次后台静态读取后，写额度仍允许首次请求加 10 个突发请求。额度耗尽后，通过媒体路径或 CSS/HTML 路径发起非 GET 请求仍返回 429，不能绕过写限制。
- 当前多图路径与历史媒体路径共用媒体额度；耗尽媒体额度不阻塞 options 和 JS。
- 60 次公开静态资源读取不占公开 API 的额度。公开 API、身份验证、位置解析保留原有过载 503 及首次允许 21、4、11 次的突发边界。内部路径仍拒绝访问。

本次 246 个断言通过。日志：`/tmp/checkin-admin-refresh-proxy.log`；逐请求响应和截图位于同目录 `admin-refresh-proxy/`。

## 重跑

从仓库根目录执行，需 Docker、可用的 Playwright 包和 Chromium/Chrome：

```sh
node services/rigour-sales-work-service/sales-work-service/scripts/admin-refresh-proxy-check.mjs
```

Playwright 不在普通 Node 模块搜索路径时设置 `PW_MODULE_PATH`；使用现有 Chrome 时设置 `CHROME_BIN`。脚本没有个人绝对路径依赖。

可选环境变量：

| 变量 | 默认值 / 用途 |
| --- | --- |
| `NGINX_PREVIOUS_REF` | `db8bc09`；只读 `git show` 获取旧模板 |
| `NGINX_TEST_IMAGE` | 固定摘要的 `nginx:1.18-alpine` |
| `QA_OUTPUT` | `docs/qa-20260908/admin-refresh-proxy` |
| `PW_MODULE_PATH` | 可正常 `require('playwright')` 时无需指定 |
| `CHROME_BIN` | 已安装的 Playwright Chromium 可用时无需指定 |

脚本逐场景使用独立容器重置限流状态，结束后删除容器和临时配置；只开放随机本机回环端口。浏览器禁止访问夹具来源以外的地址，合成上游要求每次运行的随机标识。

## 范围

本回归证明代理路由、资源可用性和限流隔离，不替代真实后台业务功能、Cookie 鉴权或生产 HTTPS 的端到端验收。正常浏览场景使用每步 750ms 操作间隔和图片 80ms 合成响应；高频请求另行验证拒绝行为，不通过自动重试掩盖 429/503。上传体积边界由 `scripts/nginx-upload-check.mjs` 独立验证。
