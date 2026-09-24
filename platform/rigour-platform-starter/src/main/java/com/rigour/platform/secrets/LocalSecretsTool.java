package com.rigour.platform.secrets;

import java.io.Console;
import java.nio.file.Path;
import java.util.Arrays;

/** 仅在人工录入/校验时运行，不提供明文导出命令，也不接受命令行密码。 */
public final class LocalSecretsTool {
    private LocalSecretsTool() {
    }

    public static void main(String[] args) {
        try {
            if (args.length < 2) {
                throw new IllegalArgumentException("参数不足");
            }
            LocalSecretStore store = new LocalSecretStore(Path.of(args[1]));
            switch (args[0]) {
                case "init" -> {
                    if (args.length != 2) {
                        throw new IllegalArgumentException("参数数量不正确");
                    }
                    store.initialize();
                }
                case "set" -> {
                    if (args.length != 4) {
                        throw new IllegalArgumentException("参数数量不正确");
                    }
                    Console console = System.console();
                    if (console == null) {
                        throw new IllegalArgumentException("set 必须在交互式终端运行，禁止管道输入密码");
                    }
                    char[] first = console.readPassword("输入当前值（不回显）: ");
                    char[] second = null;
                    try {
                        second = console.readPassword("再次输入: ");
                        if (first == null || second == null || !Arrays.equals(first, second)) {
                            throw new IllegalArgumentException("两次输入不一致");
                        }
                        store.set(args[2], args[3], new String(first));
                    } finally {
                        if (first != null) {
                            Arrays.fill(first, '\0');
                        }
                        if (second != null) {
                            Arrays.fill(second, '\0');
                        }
                    }
                }
                case "check" -> {
                    if (args.length != 3) {
                        throw new IllegalArgumentException("参数数量不正确");
                    }
                    store.load(args[2]);
                }
                default -> throw new IllegalArgumentException("未知操作");
            }
            System.out.println("OK（未输出凭据；check 仅验证本机密文，不验证外部连接）");
        } catch (Exception e) {
            // 不打印异常对象/堆栈，解析器和文件系统异常可能包含用户输入。
            System.err.println("失败：检查参数、700/600 权限、主密钥和必填项；不会回退到 Nacos 明文。");
            System.err.println("用法：init <目录> | set <目录> <common/服务> <Spring属性名> | check <目录> <服务>");
            System.exit(1);
        }
    }
}
