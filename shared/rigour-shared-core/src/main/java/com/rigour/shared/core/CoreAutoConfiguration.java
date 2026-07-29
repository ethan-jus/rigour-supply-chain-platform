package com.rigour.shared.core;

import com.rigour.shared.core.web.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * 注册统一异常处理器。
 * 不使用包扫描，避免 core 未来新增的普通类型被误当成运行时组件。
 */
@AutoConfiguration
@Import(GlobalExceptionHandler.class)
public class CoreAutoConfiguration {
}
