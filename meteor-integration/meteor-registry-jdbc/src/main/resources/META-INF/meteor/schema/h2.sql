-- Registry schema. Run this script against a new empty database.
-- expires_at stores UTC epoch milliseconds.

CREATE TABLE meteor_sessions
(
    session_id VARCHAR(36) NOT NULL,
    expires_at BIGINT      NOT NULL,
    PRIMARY KEY (session_id)
);

CREATE TABLE meteor_applications
(
    application_id VARCHAR(64) NOT NULL,
    search_text    CLOB        NOT NULL,
    revision       VARCHAR(36) NOT NULL,
    body           CLOB        NOT NULL,
    PRIMARY KEY (application_id)
);

CREATE TABLE meteor_instances
(
    instance_id      VARCHAR(64) NOT NULL,
    application_id   VARCHAR(64) NOT NULL,
    unit_name        VARCHAR(256),
    search_text      CLOB        NOT NULL,
    revision         VARCHAR(36) NOT NULL,
    body             CLOB        NOT NULL,
    -- Keep the latest registration session after runtime departure.
    session_id       VARCHAR(36) NOT NULL,
    runtime_revision VARCHAR(36) NULL,
    runtime_body     CLOB NULL,
    rule_controls    CLOB NULL,
    PRIMARY KEY (instance_id)
);

CREATE TABLE meteor_services
(
    service_id    VARCHAR(64) NOT NULL,
    search_text   CLOB        NOT NULL,
    revision      VARCHAR(36) NOT NULL,
    rule_controls CLOB,
    body          CLOB        NOT NULL,
    PRIMARY KEY (service_id)
);

CREATE TABLE meteor_instance_services
(
    instance_id     VARCHAR(64) NOT NULL,
    service_id      VARCHAR(64) NOT NULL,
    member_role     VARCHAR(16) NOT NULL,
    session_id      VARCHAR(36) NOT NULL,
    search_text     CLOB        NOT NULL,
    search_role     CLOB,
    search_protocol CLOB,
    search_address  CLOB,
    revision        VARCHAR(36) NOT NULL,
    rule_controls   CLOB,
    body            CLOB        NOT NULL,
    PRIMARY KEY (instance_id, service_id)
);

CREATE TABLE meteor_member_protocols
(
    service_id    VARCHAR(64)  NOT NULL,
    instance_id   VARCHAR(64)  NOT NULL,
    protocol_name VARCHAR(256) NOT NULL,
    session_id    VARCHAR(36)  NOT NULL,
    PRIMARY KEY (service_id, instance_id, protocol_name)
);

CREATE TABLE meteor_governance
(
    instance_id   VARCHAR(64) NOT NULL,
    service_id    VARCHAR(64) NOT NULL,
    item_name     VARCHAR(64) NOT NULL,
    revision      VARCHAR(36) NOT NULL,
    session_id    VARCHAR(36),
    rule_controls CLOB,
    body          CLOB,
    PRIMARY KEY (instance_id, service_id, item_name)
);

-- Global state also holds rule switches when no state override exists.
INSERT INTO meteor_governance(instance_id, service_id, item_name, revision, body)
VALUES ('global', 'global', 'state', '00000000-0000-0000-0000-000000000000', NULL);

CREATE INDEX met_governance_session ON meteor_governance (session_id);

CREATE INDEX met_sessions_expiry ON meteor_sessions (expires_at);

CREATE INDEX met_instances_application ON meteor_instances (application_id, instance_id);

CREATE INDEX met_instances_unit ON meteor_instances (application_id, unit_name, instance_id);

CREATE INDEX met_instances_session ON meteor_instances (session_id);

CREATE INDEX met_iservices_session ON meteor_instance_services (session_id);

CREATE INDEX met_iservices_members ON meteor_instance_services (service_id, member_role, instance_id);

CREATE INDEX met_protocols_session ON meteor_member_protocols (session_id);

CREATE INDEX met_protocols_instance ON meteor_member_protocols (instance_id, session_id);
