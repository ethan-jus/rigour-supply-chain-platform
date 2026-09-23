import java.math.BigInteger;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

/**
 * JDK 21 source launcher; prepares a private key and reviewable SQL, never connects to a database.
 * Only for an independent production database containing the single broken DEV signing record.
 * Usage: java PrepareProductionSigningKey.java /absolute/key.pem /absolute/init.sql
 * Run on the IAM server as the IAM OS user. Neither output may already exist.
 */
public final class PrepareProductionSigningKey {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected absolute private-key path and SQL output path");
        }
        Path keyPath = absolutePath(args[0]);
        Path sqlPath = absolutePath(args[1]);
        if (keyPath.equals(sqlPath)) {
            throw new IllegalArgumentException("Key and SQL must use different paths");
        }
        for (Path path : new Path[]{keyPath, sqlPath}) {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Will not overwrite existing file: " + path);
            }
            if (!Files.isDirectory(path.getParent()) || Files.isSymbolicLink(path.getParent())) {
                throw new IllegalArgumentException("Create a real parent directory first: " + path.getParent());
            }
        }
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        var pair = generator.generateKeyPair();
        var publicKey = (RSAPublicKey) pair.getPublic();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String jwk = "{\"kty\":\"RSA\",\"kid\":\"rigour-prod-signing-v1\",\"use\":\"sig\","
                + "\"alg\":\"RS256\",\"n\":\"" + unsigned(publicKey.getModulus())
                + "\",\"e\":\"" + unsigned(publicKey.getPublicExponent()) + "\"}";
        String routine = "rg_init_prod_signing_" + System.currentTimeMillis();
        String sql = """
                -- Requires IAM to be stopped and a backup of iam_signing_key.
                -- Contains only the public JWK and private-file reference, never private key material.
                -- This retires the copied DEV key; tokens signed with it will no longer be accepted.
                -- Run with mysql in batch mode WITHOUT --force; stop on any error.
                DELIMITER $$
                CREATE PROCEDURE %1$s()
                SQL SECURITY INVOKER
                BEGIN
                    DECLARE EXIT HANDLER FOR SQLEXCEPTION
                    BEGIN
                        ROLLBACK;
                        RESIGNAL;
                    END;
                    IF DATABASE() <> 'rigour_iam' OR @@hostname <> 'train' OR @@port <> 3306 THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Wrong database/server; expected train:3306/rigour_iam';
                    END IF;
                    IF NOT EXISTS (
                        SELECT 1 FROM information_schema.tables
                        WHERE table_schema=DATABASE() AND table_name='iam_signing_key' AND engine='InnoDB'
                    ) THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'iam_signing_key must exist and use InnoDB';
                    END IF;
                    START TRANSACTION;
                    SELECT kid, status FROM iam_signing_key FOR UPDATE;
                    IF (SELECT COUNT(*) FROM iam_signing_key WHERE status IN ('ACTIVE','VERIFY_ONLY')) <> 1
                       OR (SELECT COUNT(*) FROM iam_signing_key
                           WHERE kid='rigour-dev-signing-v1' AND status='ACTIVE'
                           AND COALESCE(TRIM(private_key_ref), '')='') <> 1 THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Unexpected key catalog; no changes applied';
                    END IF;
                    IF EXISTS (SELECT 1 FROM iam_signing_key WHERE kid='rigour-prod-signing-v1') THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Production key already registered; no changes applied';
                    END IF;
                    UPDATE iam_signing_key
                       SET status='RETIRED', retired_at=UTC_TIMESTAMP(6)
                     WHERE kid='rigour-dev-signing-v1' AND status='ACTIVE';
                    INSERT INTO iam_signing_key
                        (id, kid, algorithm, key_use, public_jwk_json, private_key_ref, status,
                         not_before, not_after, activated_at, retired_at, created_at, created_by)
                    VALUES
                        (UNHEX(REPLACE(UUID(), '-', '')), 'rigour-prod-signing-v1', 'RS256', 'sig',
                         '%2$s', 'file:%3$s', 'ACTIVE',
                         UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE,
                         UTC_TIMESTAMP(6) + INTERVAL 1 YEAR,
                         UTC_TIMESTAMP(6), NULL, UTC_TIMESTAMP(6), NULL);
                    COMMIT;
                END$$
                DELIMITER ;
                CALL %1$s();
                DROP PROCEDURE %1$s;
                SELECT kid, status, private_key_ref, not_before, not_after FROM iam_signing_key;
                """.formatted(routine, jwk, keyPath);
        writeRestrictedNewFile(keyPath, pem);
        writeRestrictedNewFile(sqlPath, sql);
        System.out.println("Private key prepared (PKCS#8 RSA-3072, mode 600): " + keyPath);
        System.out.println("Public-key registration SQL prepared: " + sqlPath);
        System.out.println("No database was changed. Keep the private key on this server.");
    }

    private static Path absolutePath(String value) {
        // SQL receives this path as a literal; reject quoting, backslashes and control characters.
        if (!value.matches("/[A-Za-z0-9_./-]+")) {
            throw new IllegalArgumentException("Use an absolute path containing only letters, digits, /, _, . and -");
        }
        return Path.of(value).normalize();
    }

    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void writeRestrictedNewFile(Path path, String content) throws Exception {
        try (SeekableByteChannel channel = Files.newByteChannel(path,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
             var writer = Channels.newWriter(channel, StandardCharsets.US_ASCII)) {
            writer.write(content);
        }
    }
}
