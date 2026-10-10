/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
-- Execute once before opening the JDBC admin. expires_at is UTC epoch milliseconds.
CREATE TABLE meteor_sessions
(
    scope      NVARCHAR(128) COLLATE Latin1_General_100_BIN2 NOT NULL,
    owner_id   NVARCHAR(36) COLLATE Latin1_General_100_BIN2 NOT NULL,
    expires_at BIGINT NOT NULL,
    PRIMARY KEY (scope, owner_id)
);

CREATE TABLE meteor_records
(
    scope       NVARCHAR(128) COLLATE Latin1_General_100_BIN2 NOT NULL,
    record_key  NVARCHAR(64) COLLATE Latin1_General_100_BIN2 NOT NULL,
    kind        NVARCHAR(1) COLLATE Latin1_General_100_BIN2 NOT NULL,
    record_type NVARCHAR(64) COLLATE Latin1_General_100_BIN2 NOT NULL,
    source_ref  NVARCHAR(64) COLLATE Latin1_General_100_BIN2,
    target_ref  NVARCHAR(64) COLLATE Latin1_General_100_BIN2,
    owner_id    NVARCHAR(36) COLLATE Latin1_General_100_BIN2,
    revision    NVARCHAR(36) COLLATE Latin1_General_100_BIN2 NOT NULL,
    body        NVARCHAR(MAX) NOT NULL,
    PRIMARY KEY (scope, record_key)
);

CREATE INDEX meteor_records_type ON meteor_records (scope, kind, record_type);
CREATE INDEX meteor_records_source ON meteor_records (scope, source_ref, record_type);
CREATE INDEX meteor_records_target ON meteor_records (scope, target_ref, record_type);
CREATE INDEX meteor_records_owner ON meteor_records (scope, owner_id);
CREATE INDEX meteor_sessions_expiry ON meteor_sessions (scope, expires_at);
