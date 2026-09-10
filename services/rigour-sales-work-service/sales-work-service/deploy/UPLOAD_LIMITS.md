# 拜访录音上传上限与代理验证

本次兼容已报告的约 190 MB、11 分 31 秒 WAV。该文件超过旧版的业务 100 MiB、Spring 单文件 100 MB / 整请求 105 MB，以及 Nginx 110m 三层限制；只提高 Nginx 仍会被应用拒绝。设备已确认为荣耀 100 Pro、MagicOS 9、Android 15，不能把这次 HTTP 413 归因于鸿蒙或录音格式。

## 同步配置

| 层级 | 新上限 | 配置位置 |
| --- | --- | --- |
| 业务单段录音 | 268435456 字节，即 256 MiB | `RIGOUR_SALES_TEMPORARY_CHECKIN_MAX_AUDIO_BYTES` / `application.yml` |
| Spring 单文件 | 256MB | `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` |
| Spring 整个 multipart 请求 | 260MB | `SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE` |
| Nginx 公开 API 请求体 | 270m | `nginx/sales-checkin-locations.conf` 的 `/sales-checkin/api/` |

Spring 的 MB 与 Nginx 的 m 在此按二进制单位处理；给 multipart 边界与元数据留出余量。前端从 `GET /sales-checkin/api/v1/options` 的 `maxAudioBytes` 读取业务上限，并在选择文件时预检；环境覆盖业务上限时，必须同时核对另外两层，不能只改一个变量。单次拜访总录音容量与段数仍由既有业务配置控制。

本次不放宽管理 API 的 30m、定位解析和身份验证的 64k。原有会话/个人身份验证、限速、并发限制、受信代理标记、`proxy_request_buffering off` 和上传超时保持不变。公开 API 代理已经有 `proxy_http_version 1.1`。

## 发布前只读预检

历史交接记录的部署链为：公网 HTTPS → Nginx → `127.0.0.1:26886` → `rigour-sales-checkin` 容器。仓库的 Spring API Gateway 只路由 `/api/v1/sales/**`，不含 `/sales-checkin/**`，不能假设本入口经过它。服务器上的实际 include、虚拟主机、额外代理/CDN 和环境覆盖必须现场核对；截图中的 `nginx/1.18.0 (Ubuntu)` 只证明有 Nginx 返回 413，不能定位是哪一层。

以下命令需在已授权的服务器执行，不修改服务；不要输出完整 `nginx -T`、`runtime.env`、容器环境或 Cookie。Nginx 的受信代理标记在私有 snippet 中，不能复制到日志或仓库。

```sh
# 检查当前实际加载配置的语法。
sudo nginx -t

# 仅显示文件来源与非敏感入口/限制指令；不会打印 proxy_set_header 私有标记。
sudo nginx -T 2>/dev/null | awk '
  /^# configuration file / { print; next }
  /^[[:space:]]*(listen|server_name|location|client_max_body_size|client_body_timeout|proxy_connect_timeout|proxy_send_timeout|proxy_read_timeout|proxy_request_buffering)[[:space:]]/ { print $1, $2 }
'

# 仅查看这三个非敏感变量，输出顺序与参数顺序相同；未设置表示仍需核对配置文件/外部配置。
sudo docker exec rigour-sales-checkin printenv RIGOUR_SALES_TEMPORARY_CHECKIN_MAX_AUDIO_BYTES SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE

# 只提取应用真正返回的业务上限，避免输出人员/城市等完整 options。
curl -fsS https://rgtennis.vertexlab.cloud/sales-checkin/api/v1/options | python3 -c 'import json,sys; print("maxAudioBytes=" + str(json.load(sys.stdin).get("maxAudioBytes")))'

# 确认端口映射；不读取完整容器启动环境。
sudo docker port rigour-sales-checkin 26886
```

上限预检应得到 `268435456`、`256MB`、`260MB` 和上传命中的 `270m`。若公网与回环请求结果不同，应按同一测试请求的时间、路径、HTTP 状态和上游状态比对各层日志；不要把提交密钥、个人码、Cookie、文件内容写入诊断日志。`upstream_status` 为空的 Nginx 413 与上游返回的 413 是不同故障来源。

## 本地可重复代理测试

```sh
node services/rigour-sales-work-service/sales-work-service/scripts/nginx-upload-check.mjs
```

需要 Docker Desktop 和 Node。脚本使用固定摘要的官方 Nginx 1.18 Alpine 镜像，与仓库原始 location/limit 模板；只替换测试上游地址和无敏感值的代理标记。容器公开端口仅绑定本机 `127.0.0.1`，上游也只监听本机。Linux 环境如无 `host.docker.internal`，需在隔离测试环境准备等效地址后再运行。

脚本先执行真实 `nginx -t`，再用恒定小缓冲流式发送合成 multipart 数据，验证 1 / 190 / 256 MiB 的文件全部字节到达本地计数服务；验证公开 API 270 MiB + 1 字节、管理 API 30 MiB + 1 字节、定位和身份接口 64 KiB + 1 字节均返回 413，且不会到达上游。使用 `Expect: 100-continue` 验证已知超限请求无需发送整个大文件。退出时删除专用容器与临时配置。

这些是代理传输/边界验证，合成内容不是真实客户录音，也不验证 Java 媒体识别、对象存储或手机浏览器。应用测试与实际设备验收仍需覆盖下面的闭环。

## 应用与手机验收

1. 在隔离测试数据中上传合法 190 MiB 左右 WAV，确认原件字节数和哈希、后台段数/时长、兼容播放副本和人工复核不丢失；重复同一请求不重复登记。
2. 分别检查业务 256 MiB 边界、Spring 文件/整请求边界和代理 270 MiB 边界。MockMvc 不能替代真实 Servlet multipart 和 Nginx 测试。
3. 荣耀 100 Pro / MagicOS 9 / Android 15 分别验证普通浏览器和实际销售使用的内置浏览器。弱网、后台切换、刷新、重复点击和上传中断后，主拜访可先完成，媒体状态准确保留，并可继续补传。不得把传输 99% 当作上传成功；必须收到匹配的服务端回执。
4. HTML 或 JSON 413 都显示明确中文终态，不展示 HTML，不自动反复重传同一超限原件，不阻止必填照片齐全的主拜访提交。
5. 验证临时目录与容器磁盘容量、并发大文件上传和 ffmpeg 转码资源。业务哈希读取为小块流式，不能改成整文件装入 JVM 或浏览器内存；不要通过无限扩大 JVM 堆来替代流式处理。保持原始录音，再异步生成较小播放副本。

## 执行发布时

只有获得部署授权后，才备份当前实际 Nginx include、发布应用与同步三项非敏感上限、替换对应公开 API 模板。先 `nginx -t` 成功，再 reload；单改仓库模板不会改变运行中的 Nginx。发布后重新执行只读预检与受控的隔离测试验收，保留原代理配置和原应用镜像作为配对回滚点。

Nginx `client_body_timeout` 是相邻两次读取之间的空闲超时，并不是整个上传的总时限；`proxy_request_buffering off` 也不会绕过请求体大小限制。参见 [Nginx 请求体上限和超时](https://nginx.org/en/docs/http/ngx_http_core_module.html#client_max_body_size)、[请求代理缓冲](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_request_buffering)。
