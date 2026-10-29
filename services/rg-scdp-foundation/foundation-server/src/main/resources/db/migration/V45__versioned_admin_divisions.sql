-- Public administrative reference data. Published versions are immutable.
CREATE TABLE admin_division_version (
 version_code VARCHAR(32) NOT NULL, source_name VARCHAR(200) NOT NULL, source_ref VARCHAR(500) NOT NULL,
 source_published_on DATE NULL, effective_from DATE NOT NULL, status_code VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
 published_at DATETIME(6) NULL, published_by VARCHAR(64) NULL,
 created_at DATETIME(6) NOT NULL, created_by VARCHAR(64) NOT NULL, revision BIGINT NOT NULL DEFAULT 1,
 PRIMARY KEY(version_code), KEY ix_admin_version_status(status_code,effective_from),
 CHECK(status_code IN ('DRAFT','PUBLISHED','RETIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE admin_division (
 version_code VARCHAR(32) NOT NULL, admin_code CHAR(6) NOT NULL, admin_name VARCHAR(100) NOT NULL,
 admin_level VARCHAR(16) NOT NULL, parent_code CHAR(6) NULL, region_code VARCHAR(16) NULL,
 status_code VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', valid_from DATE NULL, valid_to DATE NULL,
 created_at DATETIME(6) NOT NULL, created_by VARCHAR(64) NOT NULL, revision BIGINT NOT NULL DEFAULT 1,
 PRIMARY KEY(version_code,admin_code), KEY ix_admin_parent(version_code,parent_code,status_code,admin_code),
 KEY ix_admin_region(version_code,region_code,status_code,admin_code),
 FOREIGN KEY(version_code) REFERENCES admin_division_version(version_code),
 FOREIGN KEY(version_code,parent_code) REFERENCES admin_division(version_code,admin_code),
 CHECK(admin_level IN ('PROVINCE','PREFECTURE','COUNTY')),
 CHECK(status_code IN ('ACTIVE','RETIRED')),
 CHECK(parent_code IS NULL OR parent_code<>admin_code),
 CHECK((admin_level='PROVINCE' AND parent_code IS NULL AND region_code IS NOT NULL)
    OR (admin_level<>'PROVINCE' AND parent_code IS NOT NULL AND region_code IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
