package com.rigour.tenant.iam.infrastructure.security.oidc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Pattern;

/** 仅解析环境Secret或受限绝对路径中的未加密PKCS#8 RSA私钥。 */
public final class PrivateKeyReferenceResolver {

    private static final int MAXIMUM_PEM_BYTES = 64 * 1024;
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");
    private static final Set<PosixFilePermission> FORBIDDEN_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE
    );
    private final Path homeDirectory;

    public PrivateKeyReferenceResolver() {
        this(Path.of(System.getProperty("user.home")));
    }

    PrivateKeyReferenceResolver(Path homeDirectory) {
        this.homeDirectory = homeDirectory.toAbsolutePath().normalize();
    }

    public PrivateKey resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalStateException("Signing private key reference cannot be blank");
        }
        String pem;
        if (reference.startsWith("env:")) {
            String variableName = reference.substring("env:".length());
            if (!ENVIRONMENT_NAME.matcher(variableName).matches()) {
                throw new IllegalStateException("Signing private key environment reference is invalid");
            }
            pem = System.getenv(variableName);
            if (pem == null || pem.isBlank()) {
                throw new IllegalStateException("Signing private key environment Secret is unavailable");
            }
        } else if (reference.startsWith("home-file:")) {
            pem = readHomeFile(reference.substring("home-file:".length()));
        } else if (reference.startsWith("file:")) {
            pem = readRestrictedFile(reference.substring("file:".length()));
        } else {
            throw new IllegalStateException("Signing private key reference must use env:, home-file: or file:");
        }
        return parsePkcs8(pem);
    }

    private String readHomeFile(String pathText) {
        Path relative = Path.of(pathText).normalize();
        if (pathText.isBlank() || relative.isAbsolute() || relative.startsWith("..")) {
            throw new IllegalStateException("Signing private key home-file path must stay below user.home");
        }
        try {
            Path realHome = homeDirectory.toRealPath();
            Path candidate = realHome.resolve(relative).normalize();
            if (!candidate.startsWith(realHome) || Files.isSymbolicLink(candidate)) {
                throw new IllegalStateException("Signing private key home-file path must stay below user.home");
            }
            Path realFile = candidate.toRealPath();
            if (!realFile.startsWith(realHome)) {
                throw new IllegalStateException("Signing private key home-file path escapes user.home");
            }
            return readRestrictedPath(realFile);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read signing private key home-file", exception);
        }
    }

    private String readRestrictedFile(String pathText) {
        Path path = Path.of(pathText).normalize();
        if (!path.isAbsolute()) {
            throw new IllegalStateException("Signing private key must be a regular, non-symlink absolute file");
        }
        return readRestrictedPath(path);
    }

    private String readRestrictedPath(Path path) {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
            throw new IllegalStateException("Signing private key must be a regular, non-symlink file");
        }
        try {
            long size = Files.size(path);
            if (size <= 0 || size > MAXIMUM_PEM_BYTES) {
                throw new IllegalStateException("Signing private key file size is invalid");
            }
            enforceRestrictedPermissions(path);
            return Files.readString(path, StandardCharsets.US_ASCII);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read signing private key file", exception);
        }
    }

    private static void enforceRestrictedPermissions(Path path) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            if (permissions.stream().anyMatch(FORBIDDEN_FILE_PERMISSIONS::contains)) {
                throw new IllegalStateException("Signing private key file is readable or writable by group/others");
            }
        } catch (UnsupportedOperationException ignored) {
            // 非POSIX文件系统无法读取权限位；仍保留绝对路径、普通文件和非符号链接限制。
        }
    }

    private static PrivateKey parsePkcs8(String pem) {
        if (!pem.contains("-----BEGIN PRIVATE KEY-----") || !pem.contains("-----END PRIVATE KEY-----")) {
            throw new IllegalStateException("Signing private key must be unencrypted PKCS#8 PEM");
        }
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] encoded = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (IllegalArgumentException | InvalidKeySpecException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot parse RSA PKCS#8 signing private key", exception);
        }
    }
}
