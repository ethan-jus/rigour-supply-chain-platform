# Rigour Collaboration Service

内部协作服务拥有公司员工 IM、群成员、附件、设备登记和会议记录。它不写 Sales Work 表，也不把消息正文放入 RocketMQ；后续推送和审计从本服务 outbox 消费。

## 边界

- IAM 继续拥有用户、租户、组织和登录会话。
- Sales Work 只通过门店、拜访、任务等不可变业务 ID 与协作消息关联。
- LiveKit 只作为会议媒体面，入会权限和会议记录由本服务控制。

## 主要接口

- `/api/v1/collaboration/directory`
- `/api/v1/conversations`
- `/api/v1/conversations/{conversationId}/messages`
- `/api/v1/messages/{messageId}/recall`
- `/api/v1/meetings`
- `/api/v1/meetings/{meetingId}/join-token`
- `/api/v1/devices`
- `/ws/v1/collaboration`

## 运行配置

LiveKit 参数通过环境变量或 Nacos 注入：

- `RIGOUR_LIVEKIT_URL`
- `RIGOUR_LIVEKIT_API_KEY`
- `RIGOUR_LIVEKIT_API_SECRET`

真实 APNs、FCM 和国内厂商推送尚未在本模块内发送；当前先完成设备登记和 outbox 事件，避免在消息事务里耦合外部推送。
