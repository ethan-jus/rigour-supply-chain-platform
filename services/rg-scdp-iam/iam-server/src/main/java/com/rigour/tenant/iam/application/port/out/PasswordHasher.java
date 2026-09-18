package com.rigour.tenant.iam.application.port.out;

/** 密码强哈希出站端口；调用方不得记录、缓存或持久化原始密码。 */
public interface PasswordHasher {

    String hash(CharSequence rawPassword);

    boolean matches(CharSequence rawPassword, String encodedPassword);

    boolean needsUpgrade(String encodedPassword);

    /** 对不存在的账号执行等价强度计算，降低账号枚举的时序差异。 */
    void consumeDummyVerification(CharSequence rawPassword);
}
