# 业务服务用户上下文与授权接入规范

## 结论

登录成功进入其他系统后，业务服务需要获得当前调用人的稳定身份和权限，但不应在每个请求里转发完整用户资料。授权使用Gateway签名的最小上下文；姓名、头像、组织详情等显示资料由Portal调用IAM `/api/v1/me`获取，或由业务服务维护可追溯的只读投影。

## 请求信任链

1. 浏览器只发送Bearer Access Token、`X-Request-Id`和语言信息，禁止自行发送`X-Rigour-*`身份头。
2. Gateway验证JWT签名、issuer、audience、`tokenUse`和必需声明，再调用IAM内部`/api/v1/token/current`检查当前会话、用户安全版本、租户策略版本并取得最新角色/权限。
3. Gateway删除所有客户端`X-Rigour-*`，用独立HMAC密钥签名调用人、租户、会话版本、角色、权限、HTTP方法、路径和查询。
4. 领域服务的`RequestContextFilter`验证签名和30秒时间窗口后建立`CallerIdentity`；未签名、篡改、超限或过期上下文返回401。
5. Controller或应用服务执行权限检查；数据归属仍必须在仓储SQL中以当前`tenantId`和领域数据范围约束，不能只依赖页面隐藏。

## 业务代码模板

```java
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;

public OrderView getOrder(UUID orderId) {
    CallerIdentity caller = AuthorizationContext.requireCurrent();
    AuthorizationContext.requirePermission("order:order:read");

    UUID tenantId = caller.tenantId();
    return orderRepository.findByTenantIdAndId(tenantId, orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
}
```

- `requireCurrent()`保证请求来自已认证调用人。
- `requirePermission(code)`失败统一映射为HTTP 403。
- 租户业务必须使用`caller.tenantId()`作为查询/写入边界；不得接受请求体中的tenantId覆盖它。
- 平台调用人的`tenantId`为空，只有明确设计的平台用例才能接受`principalScope=PLATFORM`。
- `roles`适合管理界面展示或少量角色语义；后端功能授权以稳定`permissionCode`为准。

## 用户资料怎么取

| 数据 | 来源 | 是否用于授权 |
|---|---|---:|
| principalScope、principalId、tenantId、sessionId | Gateway签名上下文 | 是 |
| roles、permissions、安全/策略版本 | IAM当前事实，经Gateway签名 | 是 |
| displayName、tenantName | Portal的IAM `/me`响应或本地只读投影 | 否 |
| 组织名称、头像、手机号等可变资料 | 对应主数据服务或事件投影 | 否 |

业务服务需要记录“操作人”时保存稳定`principalId`和必要的当时显示名快照；审计事实仍以稳定ID为准。服务间异步消息不得复制HTTP ThreadLocal，必须在事件契约中显式携带tenantId、actorId、correlationId并由消费者校验消息来源。

## 环境配置

Gateway、IAM和所有接收Gateway流量的领域服务必须由Secret注入同一份当前密钥：

```yaml
rigour:
  context:
    trust:
      active-key-id: v1
      keys-base64:
        v1: ${RIGOUR_CONTEXT_TRUST_KEY_V1:}
```

密钥至少32随机字节并Base64编码，不得提交Git、写入Nacos明文或复用OIDC签名/AES/数据库密钥。轮换时先让下游同时接受v1/v2，再切换Gateway的`active-key-id`，最后移除旧密钥。

## 新业务验收门禁

- 带真实Access Token经Gateway访问成功；无Token或失效会话返回401。
- 伪造或篡改任一`X-Rigour-*`直连服务返回401。
- 有身份但缺权限返回403；页面隐藏不能作为后端授权证据。
- A租户用户不能读取、更新或推断B租户数据。
- 路径、查询参数改变后旧签名不能复用；超过时间窗口的签名不能重放。
- 禁用用户、重置密码、退出或角色/套餐变化后，下一次Gateway请求使用IAM最新事实。
