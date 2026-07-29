/**
 * 缓存契约模块。
 *
 * <p>职责：定义租户隔离的二进制缓存端口。</p>
 * <p>边界：不绑定 Redis，不自动缓存方法，也不能保存唯一业务事实。</p>
 */
package com.rigour.shared.cache;
