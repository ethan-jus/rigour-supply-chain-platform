#!/usr/bin/env python3
"""Freeze fresh-schema baselines without modifying applied Flyway V migrations.

--create is a one-time repository operation, not a database operation.
--check also checks newly added V migrations for the collation policy.
Once published, neither a baseline nor its historical sources may be regenerated.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "scripts/database/general-ci-baselines.json"
COLLATION = "utf8mb4_general_ci"
COLLATIONS = re.compile(r"\butf8mb4_[a-z0-9_]+\b", re.I)


def version(path):
    return tuple(int(part) for part in re.split(r"[._]", path.name[1:].split("__")[0]))


def source_hash(paths):
    digest = hashlib.sha256()
    for path in paths:
        digest.update(path.name.encode() + b"\0" + path.read_bytes() + b"\0")
    return digest.hexdigest()


def baseline(paths):
    parts = ["-- Frozen fresh-schema baseline. Do not edit after deployment.\n"
             "-- Historical V migrations remain available for existing databases.\n"
             "-- Contains built-in configuration seeds, not an import of historical business data.\n"
             "ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;\n"]
    for path in paths:
        parts.append("\n-- Source: " + path.name + "\n"
                     + COLLATIONS.sub(COLLATION, path.read_text()).rstrip() + "\n")
    # Flyway creates its history before executing this baseline, possibly under an old DB default.
    parts.append("\nALTER TABLE flyway_schema_history CONVERT TO CHARACTER SET utf8mb4 "
                 "COLLATE utf8mb4_general_ci;\n")
    return "".join(parts).encode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_mutually_exclusive_group(required=True)
    actions.add_argument("--create", action="store_true")
    actions.add_argument("--check", action="store_true")
    args = parser.parse_args()
    directories = sorted(ROOT.glob("services/*/*-server/src/main/resources/db/migration"))
    if args.create:
        if MANIFEST.exists():
            raise SystemExit("Manifest already exists. Published baselines must not be regenerated.")
        entries = []
        for directory in directories:
            paths = sorted(directory.glob("V*__*.sql"), key=version)
            if not paths:
                continue
            last = paths[-1].name[1:].split("__")[0]
            output = directory.parent / "bootstrap" / ("B" + last + "__general_ci_bootstrap.sql")
            if output.exists():
                raise SystemExit("Refusing to overwrite " + str(output))
            content = baseline(paths)
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(content)
            entries.append({"migration_directory": str(directory.relative_to(ROOT)),
                            "through_version": last, "migration_count": len(paths),
                            "source_sha256": source_hash(paths),
                            "baseline": str(output.relative_to(ROOT)),
                            "baseline_sha256": hashlib.sha256(content).hexdigest()})
        MANIFEST.write_text(json.dumps(entries, indent=2) + "\n")
    entries = json.loads(MANIFEST.read_text())
    expected_dirs = {entry["migration_directory"] for entry in entries}
    actual_dirs = {str(d.relative_to(ROOT)) for d in directories if list(d.glob("V*__*.sql"))}
    if expected_dirs != actual_dirs:
        raise SystemExit("Service set changed; review the baseline manifest for the new service.")
    for entry in entries:
        directory = ROOT / entry["migration_directory"]
        paths = sorted(directory.glob("V*__*.sql"), key=version)
        ceiling = tuple(map(int, re.split(r"[._]", entry["through_version"])))
        frozen = [path for path in paths if version(path) <= ceiling]
        assert len(frozen) == entry["migration_count"], directory
        assert source_hash(frozen) == entry["source_sha256"], "Historical migration changed: " + str(directory)
        content = (ROOT / entry["baseline"]).read_bytes()
        assert hashlib.sha256(content).hexdigest() == entry["baseline_sha256"], entry["baseline"]
        assert content == baseline(frozen), "Baseline does not match its frozen sources"
        for path in paths:
            if version(path) > ceiling:
                assert all(c.lower() == COLLATION for c in COLLATIONS.findall(path.read_text())), path
    print("Verified %d frozen baselines and %d unchanged historical migrations; new migrations use %s."
          % (len(entries), sum(entry["migration_count"] for entry in entries), COLLATION))


if __name__ == "__main__":
    main()
