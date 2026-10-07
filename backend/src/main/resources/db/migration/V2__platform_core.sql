-- =====================================================================
-- V2: Platform core
--   * tms_app role: the application runs every tenant request as this
--     role, so PostgreSQL row-level security (RLS) always applies
--   * session helpers: app_current_tenant(), app_current_user()
--   * append-only audit log written by triggers
--   * helper that secures a tenant table (RLS + audit + updated_at)
--   * recycle bin legal holds
--   * gapless document number series
-- =====================================================================

-- ---------------------------------------------------------------------
-- Application role (no login; the pool user switches to it per request)
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tms_app') THEN
        CREATE ROLE tms_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
END
$$;

-- Lets a non-superuser migration user (e.g. Aurora master user) SET ROLE tms_app.
DO $$
BEGIN
    EXECUTE format('GRANT tms_app TO %I', current_user);
EXCEPTION WHEN others THEN
    NULL; -- already granted, or current user is a superuser
END
$$;

GRANT USAGE ON SCHEMA public TO tms_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tms_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO tms_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO tms_app;

-- ---------------------------------------------------------------------
-- Session context helpers (set by the backend on every connection)
-- ---------------------------------------------------------------------
CREATE FUNCTION app_current_tenant() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid $$;

CREATE FUNCTION app_current_user() RETURNS text
    LANGUAGE sql STABLE
AS $$ SELECT NULLIF(current_setting('app.user_id', true), '') $$;

-- ---------------------------------------------------------------------
-- Tenant table: each tenant sees only itself
-- ---------------------------------------------------------------------
ALTER TABLE tenant ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE tenant ADD CONSTRAINT tenant_status_chk CHECK (status IN ('ACTIVE', 'SUSPENDED', 'READ_ONLY', 'CLOSED'));

REVOKE ALL ON tenant FROM tms_app;
GRANT SELECT ON tenant TO tms_app;
ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_self ON tenant USING (id = app_current_tenant());

-- ---------------------------------------------------------------------
-- updated_at maintenance
-- ---------------------------------------------------------------------
CREATE FUNCTION set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END
$$;

CREATE TRIGGER tenant_updated_at BEFORE UPDATE ON tenant
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------
-- Audit log (append-only)
-- ---------------------------------------------------------------------
CREATE TABLE audit_log (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       uuid,
    table_name      text        NOT NULL,
    record_id       text,
    action          text        NOT NULL CHECK (action IN ('INSERT', 'UPDATE', 'DELETE', 'SOFT_DELETE', 'RESTORE')),
    changed_fields  text[],
    old_data        jsonb,
    new_data        jsonb,
    user_id         text,
    transaction_id  bigint      NOT NULL DEFAULT txid_current(),
    occurred_at     timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX audit_log_record_idx ON audit_log (tenant_id, table_name, record_id, occurred_at DESC);
CREATE INDEX audit_log_time_idx   ON audit_log (tenant_id, occurred_at DESC);

-- The application may only read its own tenant's audit rows; rows are
-- written exclusively by the SECURITY DEFINER trigger function below.
REVOKE ALL ON audit_log FROM tms_app;
GRANT SELECT ON audit_log TO tms_app;
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_tenant_read ON audit_log FOR SELECT USING (tenant_id = app_current_tenant());

-- Nobody (not even the table owner) can change or delete audit history.
CREATE FUNCTION audit_log_immutable() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END
$$;

CREATE TRIGGER audit_log_no_update BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_immutable();

CREATE FUNCTION audit_row() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
DECLARE
    v_old     jsonb;
    v_new     jsonb;
    v_action  text := TG_OP;
    v_changed text[];
    v_row     jsonb;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        v_old := to_jsonb(OLD);
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        v_new := to_jsonb(NEW);
    END IF;

    IF TG_OP = 'UPDATE' THEN
        SELECT array_agg(n.key ORDER BY n.key)
          INTO v_changed
          FROM jsonb_each(v_new) n
         WHERE n.key <> 'updated_at'
           AND (v_old -> n.key) IS DISTINCT FROM n.value;

        IF v_changed IS NULL THEN
            RETURN NULL; -- nothing meaningful changed
        END IF;

        -- Taking the next document number is not a business change worth auditing
        -- (the document that uses the number is audited instead).
        IF TG_TABLE_NAME = 'number_series' AND v_changed = ARRAY['next_value'] THEN
            RETURN NULL;
        END IF;

        IF (v_old ? 'deleted_at') THEN
            IF (v_old ->> 'deleted_at') IS NULL AND (v_new ->> 'deleted_at') IS NOT NULL THEN
                v_action := 'SOFT_DELETE';
            ELSIF (v_old ->> 'deleted_at') IS NOT NULL AND (v_new ->> 'deleted_at') IS NULL THEN
                v_action := 'RESTORE';
            END IF;
        END IF;
    END IF;

    v_row := COALESCE(v_new, v_old);

    INSERT INTO audit_log (tenant_id, table_name, record_id, action, changed_fields, old_data, new_data, user_id)
    VALUES (
        COALESCE((v_row ->> 'tenant_id')::uuid, app_current_tenant()),
        TG_TABLE_NAME,
        v_row ->> 'id',
        v_action,
        v_changed,
        v_old,
        v_new,
        app_current_user()
    );
    RETURN NULL;
END
$$;

REVOKE ALL ON FUNCTION audit_row() FROM PUBLIC;

-- ---------------------------------------------------------------------
-- Secures a tenant-owned table in one call:
--   RLS on tenant_id, audit trigger, updated_at trigger (if present)
-- ---------------------------------------------------------------------
CREATE FUNCTION tms_secure_tenant_table(p_table regclass) RETURNS void
    LANGUAGE plpgsql
AS $$
DECLARE
    v_name text := (SELECT relname FROM pg_class WHERE oid = p_table);
BEGIN
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', p_table);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', p_table);
    EXECUTE format(
        'CREATE POLICY tenant_isolation ON %s USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant())',
        p_table);
    EXECUTE format(
        'CREATE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON %s FOR EACH ROW EXECUTE FUNCTION audit_row()',
        v_name || '_audit', p_table);
    IF EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = p_table AND attname = 'updated_at' AND NOT attisdropped) THEN
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE ON %s FOR EACH ROW EXECUTE FUNCTION set_updated_at()',
            v_name || '_updated_at', p_table);
    END IF;
END
$$;

REVOKE ALL ON FUNCTION tms_secure_tenant_table(regclass) FROM PUBLIC, tms_app;

-- ---------------------------------------------------------------------
-- Recycle bin: legal holds block permanent deletion
-- ---------------------------------------------------------------------
CREATE TABLE legal_hold (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    table_name  text        NOT NULL,
    record_id   uuid        NOT NULL,
    reason      text        NOT NULL,
    placed_by   text,
    placed_at   timestamptz NOT NULL DEFAULT now(),
    released_at timestamptz,
    released_by text
);

CREATE UNIQUE INDEX legal_hold_active_uq ON legal_hold (tenant_id, table_name, record_id) WHERE released_at IS NULL;
SELECT tms_secure_tenant_table('legal_hold');

-- ---------------------------------------------------------------------
-- Document number series (gapless; one row per tenant/location/type/year)
-- ---------------------------------------------------------------------
CREATE TABLE number_series (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenant (id),
    location_id     uuid,
    document_type   text        NOT NULL,
    financial_year  text        NOT NULL,
    prefix          text        NOT NULL DEFAULT '',
    padding         smallint    NOT NULL DEFAULT 6 CHECK (padding BETWEEN 1 AND 12),
    next_value      bigint      NOT NULL DEFAULT 1 CHECK (next_value >= 1),
    active          boolean     NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT number_series_uq UNIQUE NULLS NOT DISTINCT (tenant_id, location_id, document_type, financial_year)
);

SELECT tms_secure_tenant_table('number_series');

-- Takes the next number inside the caller's transaction. The row lock makes
-- concurrent callers wait, and a rollback returns the number, so the series
-- never has gaps.
CREATE FUNCTION next_document_number(p_document_type text, p_location_id uuid, p_financial_year text)
    RETURNS text
    LANGUAGE plpgsql
AS $$
DECLARE
    v_number text;
BEGIN
    UPDATE number_series
       SET next_value = next_value + 1
     WHERE tenant_id = app_current_tenant()
       AND document_type = p_document_type
       AND location_id IS NOT DISTINCT FROM p_location_id
       AND financial_year = p_financial_year
       AND active
    RETURNING prefix || lpad((next_value - 1)::text, padding, '0')
      INTO v_number;

    IF v_number IS NULL THEN
        RAISE EXCEPTION 'No active number series for % / % / %', p_document_type, p_location_id, p_financial_year
            USING ERRCODE = 'P0002';
    END IF;
    RETURN v_number;
END
$$;

-- Financial year label for a date, e.g. 2026-04-01 with April start -> '2026-27';
-- calendar-year countries (start month 1) -> '2026'.
CREATE FUNCTION financial_year_label(p_date date, p_start_month smallint) RETURNS text
    LANGUAGE sql IMMUTABLE
AS $$
    SELECT CASE
        WHEN p_start_month = 1 THEN extract(year FROM p_date)::int::text
        ELSE (CASE WHEN extract(month FROM p_date) >= p_start_month
                   THEN extract(year FROM p_date)::int
                   ELSE extract(year FROM p_date)::int - 1 END)::text
             || '-'
             || lpad(((CASE WHEN extract(month FROM p_date) >= p_start_month
                            THEN extract(year FROM p_date)::int
                            ELSE extract(year FROM p_date)::int - 1 END + 1) % 100)::text, 2, '0')
    END
$$;
