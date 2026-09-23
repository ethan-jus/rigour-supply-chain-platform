import java.math.BigInteger;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

/**
 * JDK 21 source launcher. Reads the EXISTING production PEM, derives its public key and writes SQL.
 * Never connects to a database, creates a new key, or writes private key material to the output.
 * Usage: java PrepareIamSigningCatalogRepair.java /absolute/existing.pem /absolute/new-repair.sql
 * Specifically repairs the malformed DEV import confirmed on train:3306/rigour_scdp_iam.
 */
public final class PrepareIamSigningCatalogRepair {
    private static final String KID = "rigour-prod-signing-v1";
    private static final Set<PosixFilePermission> FORBIDDEN = Set.of(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected existing PEM path and new SQL output path");
        Path keyPath = absolutePath(args[0]);
        Path sqlPath = absolutePath(args[1]);
        if (keyPath.equals(sqlPath) || Files.exists(sqlPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Output must be a new file, separate from the existing PEM");
        }
        if (!Files.isDirectory(sqlPath.getParent()) || Files.isSymbolicLink(sqlPath.getParent())) {
            throw new IllegalArgumentException("SQL parent must be an existing real directory");
        }
        if (!Files.isRegularFile(keyPath, LinkOption.NOFOLLOW_LINKS)
                || Files.size(keyPath) == 0 || Files.size(keyPath) > 65536) {
            throw new IllegalArgumentException("Existing PEM must be a regular non-symlink file, 1-65536 bytes");
        }
        if (Files.getPosixFilePermissions(keyPath).stream().anyMatch(FORBIDDEN::contains)) {
            throw new IllegalStateException("Existing PEM must not be accessible by group/others; use mode 600 or 400");
        }
        String pem = Files.readString(keyPath, StandardCharsets.US_ASCII).strip();
        if (!pem.startsWith("-----BEGIN PRIVATE KEY-----") || !pem.endsWith("-----END PRIVATE KEY-----")) {
            throw new IllegalArgumentException("Expected the existing unencrypted PKCS#8 RSA PEM");
        }
        byte[] encoded = Base64.getDecoder().decode(pem
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", ""));
        RSAPrivateCrtKey privateKey;
        try {
            var parsed = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
            if (!(parsed instanceof RSAPrivateCrtKey rsa) || rsa.getModulus().bitLength() < 3072) {
                throw new IllegalArgumentException("Existing RSA CRT key must have at least 3072 bits");
            }
            privateKey = rsa;
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
        var publicKey = KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        byte[] challenge = "rigour-iam-existing-key-repair-check".getBytes(StandardCharsets.US_ASCII);
        var signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(challenge);
        byte[] signed = signature.sign();
        signature.initVerify(publicKey);
        signature.update(challenge);
        if (!signature.verify(signed)) throw new IllegalStateException("Existing PEM failed public/private matching check");

        String jwk = "{\"kty\":\"RSA\",\"kid\":\"" + KID + "\",\"use\":\"sig\",\"alg\":\"RS256\","
                + "\"n\":\"" + unsigned(privateKey.getModulus()) + "\",\"e\":\""
                + unsigned(privateKey.getPublicExponent()) + "\"}";
        String routine = "rg_repair_iam_signing_" + System.currentTimeMillis();
        String sql = """
                -- Generated from an EXISTING production PEM; contains no private key material.
                -- Stop IAM first. Use mysql batch mode WITHOUT --force; stop on the first error.
                -- Only repairs the confirmed malformed import on train:3306/rigour_scdp_iam.
                -- Accepts an empty imported catalog or its single ACTIVE rigour-dev-signing-v1 row.
                -- Preserves the entire old table as iam_signing_key_bad_import_20260922.
                -- Builds and seeds a replacement before swapping names in one RENAME TABLE statement.
                -- DDL implicitly commits. On failure do not clean up/retry blindly; inspect both tables.
                -- Re-registers the SAME production key as ACTIVE with a new one-year catalog validity.
                USE rigour_scdp_iam;
                DELIMITER $$
                CREATE PROCEDURE %1$s()
                SQL SECURITY INVOKER
                BEGIN
                    IF DATABASE() IS NULL OR DATABASE() <> 'rigour_scdp_iam'
                       OR @@hostname <> 'train' OR @@port <> 3306 THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Wrong server/database; expected train:3306/rigour_scdp_iam';
                    END IF;
                    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                        WHERE table_schema=DATABASE() AND table_name='iam_signing_key' AND engine='InnoDB') THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Expected imported InnoDB iam_signing_key table';
                    END IF;
                    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE()
                        AND table_name IN ('iam_signing_key_rebuilt_20260922','iam_signing_key_bad_import_20260922')) THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Repair/backup table already exists; inspect before retrying';
                    END IF;
                    IF (SELECT COUNT(*) FROM information_schema.columns
                        WHERE table_schema=DATABASE() AND table_name='iam_signing_key') <> 13
                       OR (SELECT COUNT(*) FROM information_schema.columns
                        WHERE table_schema=DATABASE() AND table_name='iam_signing_key' AND (
                            (column_name='id' AND data_type='longtext') OR
                            (column_name='public_jwk_' AND data_type='longtext') OR
                            (column_name='private_key' AND data_type='varchar') OR
                            (column_name='activated_a' AND data_type='date') OR
                            (column_name='not_before' AND data_type='date') OR
                            (column_name='not_after' AND data_type='date') OR
                            (column_name='created_by' AND data_type='longtext'))) <> 7 THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Table differs from the confirmed malformed import; no changes applied';
                    END IF;
                    IF EXISTS (SELECT 1 FROM information_schema.table_constraints
                        WHERE table_schema=DATABASE() AND table_name='iam_signing_key' AND constraint_type='PRIMARY KEY')
                       OR EXISTS (SELECT 1 FROM information_schema.key_column_usage
                        WHERE referenced_table_name IS NOT NULL AND (
                            (table_schema=DATABASE() AND table_name='iam_signing_key') OR
                            (referenced_table_schema=DATABASE() AND referenced_table_name='iam_signing_key')))
                       OR EXISTS (SELECT 1 FROM information_schema.triggers
                        WHERE trigger_schema=DATABASE() AND event_object_table='iam_signing_key') THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Unexpected constraints/triggers; no changes applied';
                    END IF;
                    IF (SELECT COUNT(*) FROM iam_signing_key) > 1
                       OR EXISTS (SELECT 1 FROM iam_signing_key
                          WHERE kid IS NULL OR kid <> 'rigour-dev-signing-v1' OR status IS NULL OR status <> 'ACTIVE') THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Unexpected imported key rows; no changes applied';
                    END IF;

                    CREATE TABLE iam_signing_key_rebuilt_20260922 (
                        id BINARY(16) NOT NULL,
                        kid VARCHAR(100) NOT NULL,
                        algorithm VARCHAR(16) NOT NULL,
                        key_use VARCHAR(16) NOT NULL,
                        public_jwk_json JSON NOT NULL,
                        private_key_ref VARCHAR(255) NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        not_before DATETIME(6) NOT NULL,
                        not_after DATETIME(6) NOT NULL,
                        activated_at DATETIME(6) NULL,
                        retired_at DATETIME(6) NULL,
                        created_at DATETIME(6) NOT NULL,
                        created_by BINARY(16) NULL,
                        CONSTRAINT pk_iam_signing_key PRIMARY KEY (id),
                        CONSTRAINT uk_iam_signing_key_kid UNIQUE (kid),
                        CONSTRAINT ck_iam_signing_key_algorithm CHECK (algorithm = 'RS256'),
                        CONSTRAINT ck_iam_signing_key_use CHECK (key_use = 'sig'),
                        CONSTRAINT ck_iam_signing_key_status CHECK (
                            status IN ('PENDING','ACTIVE','VERIFY_ONLY','RETIRED','REVOKED')),
                        CONSTRAINT ck_iam_signing_key_period CHECK (not_after > not_before),
                        CONSTRAINT ck_iam_signing_key_activation CHECK (
                            (status = 'PENDING' AND activated_at IS NULL)
                            OR (status <> 'PENDING' AND activated_at IS NOT NULL)),
                        CONSTRAINT ck_iam_signing_key_retirement CHECK (
                            (status IN ('RETIRED','REVOKED') AND retired_at IS NOT NULL)
                            OR (status NOT IN ('RETIRED','REVOKED') AND retired_at IS NULL)),
                        INDEX idx_iam_signing_key_status_period (status,not_before,not_after)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
                      COMMENT='只保存公钥和私钥引用的签名密钥元数据';

                    INSERT INTO iam_signing_key_rebuilt_20260922
                        (id,kid,algorithm,key_use,public_jwk_json,private_key_ref,status,
                         not_before,not_after,activated_at,retired_at,created_at,created_by)
                    VALUES (UNHEX(REPLACE(UUID(),'-','')), 'rigour-prod-signing-v1', 'RS256', 'sig',
                            '%2$s', 'file:%3$s', 'ACTIVE', UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE,
                            UTC_TIMESTAMP(6) + INTERVAL 1 YEAR, UTC_TIMESTAMP(6), NULL, UTC_TIMESTAMP(6), NULL);
                    IF (SELECT COUNT(*) FROM iam_signing_key_rebuilt_20260922
                        WHERE kid='rigour-prod-signing-v1' AND status='ACTIVE'
                          AND JSON_UNQUOTE(JSON_EXTRACT(public_jwk_json,'$.kid'))='rigour-prod-signing-v1'
                          AND private_key_ref='file:%3$s' AND not_before <= UTC_TIMESTAMP(6)
                          AND not_after > UTC_TIMESTAMP(6)) <> 1 THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Replacement validation failed; original table unchanged';
                    END IF;
                    RENAME TABLE iam_signing_key TO iam_signing_key_bad_import_20260922,
                                 iam_signing_key_rebuilt_20260922 TO iam_signing_key;
                END$$
                DELIMITER ;
                CALL %1$s();
                DROP PROCEDURE %1$s;
                SELECT kid,status,private_key_ref,not_before,not_after FROM iam_signing_key;
                SHOW CREATE TABLE iam_signing_key;
                """.formatted(routine, jwk, keyPath);
        try (var channel = Files.newByteChannel(sqlPath,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
             var writer = Channels.newWriter(channel, StandardCharsets.UTF_8)) {
            writer.write(sql);
        }
        System.out.println("Existing production PEM verified; private key was NOT changed.");
        System.out.println("Reviewable repair SQL prepared: " + sqlPath);
        System.out.println("Target: train:3306/rigour_scdp_iam; retained backup: iam_signing_key_bad_import_20260922.");
        System.out.println("No database was connected or modified. Keep the PEM on this server.");
    }

    private static Path absolutePath(String text) {
        if (!text.matches("/[A-Za-z0-9_./-]+")) {
            throw new IllegalArgumentException("Use an absolute path with only letters, digits, /, _, . and -");
        }
        return Path.of(text).normalize();
    }

    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes[0] == 0) bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
