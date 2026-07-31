package com.rigour.tenant.iam.infrastructure.security.oidc;

/** 对OAuth授权上下文进行带认证加密，密钥版本随密文单独保存以支持轮换。 */
public interface AuthorizationAttributesCipher {

    EncryptedAttributes encrypt(byte[] plaintext, byte[] additionalAuthenticatedData);

    byte[] decrypt(String keyVersion, byte[] ciphertext, byte[] additionalAuthenticatedData);

    /** 加密结果；数组在构造和读取时均复制，避免调用方修改已生成的密文。 */
    record EncryptedAttributes(String keyVersion, byte[] ciphertext) {

        public EncryptedAttributes {
            if (keyVersion == null || keyVersion.isBlank()) {
                throw new IllegalArgumentException("keyVersion cannot be blank");
            }
            if (ciphertext == null || ciphertext.length == 0) {
                throw new IllegalArgumentException("ciphertext cannot be empty");
            }
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }
    }
}
