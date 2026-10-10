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
    scope      VARCHAR(128) NOT NULL,
    owner_id   VARCHAR(36)  NOT NULL,
    expires_at BIGINT       NOT NULL,
    PRIMARY KEY (scope, owner_id)
);

CREATE TABLE meteor_records
(
    scope       VARCHAR(128) NOT NULL,
    record_key  VARCHAR(64)  NOT NULL,
    kind        VARCHAR(1)   NOT NULL,
    record_type VARCHAR(64)  NOT NULL,
    source_ref  VARCHAR(64),
    target_ref  VARCHAR(64),
    owner_id    VARCHAR(36),
    revision    VARCHAR(36)  NOT NULL,
    body        TEXT         NOT NULL,
    PRIMARY KEY (scope, record_key)
);

CREATE INDEX meteor_records_type ON meteor_records (scope, kind, record_type);
CREATE INDEX meteor_records_source ON meteor_records (scope, source_ref, record_type);
CREATE INDEX meteor_records_target ON meteor_records (scope, target_ref, record_type);
CREATE INDEX meteor_records_owner ON meteor_records (scope, owner_id);
CREATE INDEX meteor_sessions_expiry ON meteor_sessions (scope, expires_at);
