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
    scope      VARCHAR2(128 CHAR) NOT NULL,
    owner_id   VARCHAR2(36 CHAR) NOT NULL,
    expires_at NUMBER(19) NOT NULL,
    PRIMARY KEY (scope, owner_id)
);

CREATE TABLE meteor_records
(
    scope       VARCHAR2(128 CHAR) NOT NULL,
    record_key  VARCHAR2(64 CHAR) NOT NULL,
    kind        VARCHAR2(1 CHAR) NOT NULL,
    record_type VARCHAR2(64 CHAR) NOT NULL,
    source_ref  VARCHAR2(64 CHAR),
    target_ref  VARCHAR2(64 CHAR),
    owner_id    VARCHAR2(36 CHAR),
    revision    VARCHAR2(36 CHAR) NOT NULL,
    body        CLOB NOT NULL,
    PRIMARY KEY (scope, record_key)
);

CREATE INDEX meteor_records_type ON meteor_records (scope, kind, record_type);
CREATE INDEX meteor_records_source ON meteor_records (scope, source_ref, record_type);
CREATE INDEX meteor_records_target ON meteor_records (scope, target_ref, record_type);
CREATE INDEX meteor_records_owner ON meteor_records (scope, owner_id);
CREATE INDEX meteor_sessions_expiry ON meteor_sessions (scope, expires_at);
