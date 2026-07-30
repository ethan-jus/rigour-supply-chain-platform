/**
 * IAM应用服务层。
 *
 * <p>负责事务、权限失效、审计和Outbox等用例顺序，依赖domain与出站端口，不依赖具体数据库或第三方SDK。</p>
 */
package com.rigour.tenant.iam.application;
