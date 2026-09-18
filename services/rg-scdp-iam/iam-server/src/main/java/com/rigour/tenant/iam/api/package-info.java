/**
 * IAM HTTP接口层，只负责认证后的输入校验、协议转换和响应组装。
 *
 * <p>Controller只依赖application service和API model，不直接访问Mapper或数据库DO。</p>
 */
package com.rigour.tenant.iam.api;
