-- Keep manual process counts separate from source evidence and frozen confirmation snapshots.
ALTER TABLE hr_attendance_note
    ADD COLUMN assessment_override_json JSON NULL;
