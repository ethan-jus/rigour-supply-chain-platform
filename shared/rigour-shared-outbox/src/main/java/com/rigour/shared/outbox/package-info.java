/**
 * Outbox 契约模块。
 *
 * <p>职责：定义版本化事件和事务内追加端口。</p>
 * <p>边界：不包含 JPA 实体、共享表、消息中间件客户端或后台投递器，具体持久化归领域服务所有。</p>
 */
package com.rigour.shared.outbox;
