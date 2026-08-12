# 销售拜访录音 COS 配置

Sales Work 通过后端 Java SDK 上传录音到腾讯云 COS。H5 不直接访问 COS，因此 COS 凭据不得进入前端，当前链路也不需要为 H5 开放 COS CORS。

## 配置归属

| 配置 | 存放位置 | 说明 |
| --- | --- | --- |
| `storage-type` | application/Nacos | 开发为 `filesystem`，生产为 `cos` |
| `storage-dir` | application/Nacos | 仅 `filesystem` 模式使用 |
| `max-clip-bytes` | application/Nacos | 单片段上限，默认 25 MiB |
| `region` | application/Nacos | COS 地域简称，例如 `ap-guangzhou` |
| `bucket` | application/Nacos | 完整名称，格式为 `BucketName-APPID` |
| 连接/读取超时 | application/Nacos | 非敏感运行参数 |
| `server-side-encryption` | application/Nacos | `true` 时上传使用 SSE-COS AES256 |
| `SecretId` | 部署 Secret | 腾讯云访问凭据，不得写入 Git/Nacos/日志 |
| `SecretKey` | 部署 Secret | 腾讯云访问凭据，不得写入 Git/Nacos/日志 |
| `SessionToken` | 部署 Secret | 仅临时凭据模式需要 |

## 一、创建私有存储桶

1. 登录腾讯云对象存储 COS 控制台并开通服务。
2. 进入“存储桶列表”，选择“创建存储桶”。
3. 建议名称填写 `rigour-sales-recordings`；创建后实际完整名称会带 APPID，例如 `rigour-sales-recordings-1250000000`。
4. 地域选择销售服务部署地附近的地域，并记录地域简称。名称和地域创建后不能修改。
5. 访问权限选择“私有读写”，不要设置为公有读或公有读写。
6. 当前服务上传时会携带 SSE-COS `AES256` 请求头，因此配置中的 `server-side-encryption` 保持 `true`。

录音对象键由服务端生成，格式为：

```text
{tenantId}/visits/{visitId}/clips/{clipId}.{extension}
```

## 二、创建最小权限凭据

1. 在腾讯云访问管理 CAM 创建一个仅用于销售录音服务的子用户，例如 `rigour-sales-recording-service`。
2. 禁止该子用户登录控制台，只开启编程访问。
3. 创建自定义策略，资源范围限制到上一步的指定存储桶及桶内对象。
4. 只授予服务当前需要的对象操作：`PutObject`、`GetObject`、`DeleteObject`。不要授予账户级或所有 COS 资源权限。
5. 为该子用户创建 API 密钥。`SecretKey` 创建后需要立即保存，后续控制台不会再次展示。
6. 当前 COS 客户端没有实现 STS 凭据自动续期，生产先使用该专用子用户的长期密钥，并设置轮换周期及双密钥切换窗口。完成动态凭据提供器后再切换工作负载角色或 STS。

## 三、填写 Nacos 非敏感配置

在对应环境的 Nacos Data ID `rigour-sales-work-service.yaml` 中填写：

```yaml
sales:
  recording:
    storage-type: cos
    storage-dir: /opt/rigour/data/sales-recordings
    max-clip-bytes: 26214400
    cos:
      region: ap-beijing
      bucket: rigour-sales-recordings-1361731487
      secret-id: ${RIGOUR_SALES_RECORDING_COS_SECRET_ID:}
      secret-key: ${RIGOUR_SALES_RECORDING_COS_SECRET_KEY:}
      # 当前使用长期 CAM 密钥，不配置 session-token。
      connection-timeout-ms: 5000
      socket-timeout-ms: 30000
      server-side-encryption: true
```

当前已按实际存储桶写入 `ap-beijing` 和 `rigour-sales-recordings-1361731487`。请求域名由 COS SDK 根据地域和 Bucket 自动生成，不需要另配。`storage-dir` 在 COS 模式不会参与对象写入，保留它用于紧急切回本地持久卷。

该 Bucket 同时供 Integration 和 ERP 商品图片链路使用，但必须按对象前缀隔离：Sales Work 使用
`{tenantId}/visits/`，商品图片使用 `{tenantId}/product-images/`。Sales Work 的 Secret 只授予录音
前缀所需权限；Integration 和 ERP 使用各自的 Secret，分别授予商品图片写入或读取/签名权限，不能因为共用 Bucket 而共用密钥。

## 四、注入部署 Secret

`rigour-sales-work-service` 进程只需获得以下凭据变量：

```text
RIGOUR_SALES_RECORDING_COS_SECRET_ID
RIGOUR_SALES_RECORDING_COS_SECRET_KEY
```

`RIGOUR_SALES_RECORDING_COS_SESSION_TOKEN` 不是创建 Bucket 时生成的字段。只有服务通过腾讯云 STS 获取临时凭据时，STS 响应才会同时返回 `SecretId`、`SecretKey` 和 `Token`。长期 CAM API 密钥模式不配置它。当前客户端不会自动续期临时 Token，因此不要在生产启用该模式。

不要把真实值写入本文档、Nacos、Docker 镜像、启动脚本、Git 或聊天记录。修改后重启 Sales Work 服务；Nacos 刷新不能替代 COS 客户端重新初始化。

## 五、验收

1. 服务启动时不得出现 `sales.recording.cos.region未配置`、`bucket未配置` 或凭据未配置错误。
2. 在飞书真机完成“创建拜访 → 到店签到 → 录音 → 停止并上传”。
3. 页面应显示录音片段上传成功，数据库录音片段状态为 `RECEIVED`。
4. COS 控制台应出现对应租户/拜访路径的对象，Content-Type、SHA-256 自定义元数据和 SSE-COS 加密状态应存在。
5. 保存拜访结果并离店签退，确认录音门禁能够读取已上传总时长。
6. 使用无权限凭据做反向检查，确保无法访问其他存储桶。

如果 H5 后续改为浏览器直传 COS，必须另行设计 STS 临时授权、对象键约束和精确 CORS；不能复用当前服务端长期凭据。
