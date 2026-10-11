-- =====================================================================
-- V4: Rate cards (Module 8)
--   * charge heads: freight, GR charge, hamali, door collection ... with a
--     calculation method and default value per tenant
--   * rate cards: client cards (party_id set) and standard rates (party_id
--     NULL), effective dated and versioned; lines per lane with weight or
--     package slabs; client-specific charge values
-- Zones, agent selling limits, buy rates and bulk revisions come later.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Charge heads
-- ---------------------------------------------------------------------
CREATE TABLE charge_head (
    id             uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid           NOT NULL REFERENCES tenant (id),
    code           text           NOT NULL,
    name           text           NOT NULL,
    -- RATE_CARD = freight worked out from the rate card line
    calc_method    text           NOT NULL CHECK (calc_method IN (
                       'RATE_CARD', 'FIXED', 'PER_PACKAGE', 'PER_KG', 'PERCENT_OF_FREIGHT', 'PERCENT_OF_VALUE')),
    default_value  numeric(12, 4) NOT NULL DEFAULT 0 CHECK (default_value >= 0),
    min_amount     numeric(12, 2) NOT NULL DEFAULT 0 CHECK (min_amount >= 0),
    is_auto        boolean        NOT NULL DEFAULT false,   -- added to every booking automatically
    edit_control   text           NOT NULL DEFAULT 'FREE' CHECK (edit_control IN ('LOCKED', 'INCREASE_ONLY', 'FREE')),
    taxable        boolean        NOT NULL DEFAULT true,
    tax_code       text,                                    -- e.g. SAC 996511; resolved with the country pack
    sort_order     smallint       NOT NULL DEFAULT 100,
    is_system      boolean        NOT NULL DEFAULT false,
    active         boolean        NOT NULL DEFAULT true,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     text,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    deleted_at     timestamptz,
    deleted_by     text,
    delete_reason  text
);

CREATE UNIQUE INDEX charge_head_code_uq ON charge_head (tenant_id, upper(code)) WHERE deleted_at IS NULL;
SELECT tms_secure_tenant_table('charge_head');

-- Default charge heads for the current tenant. Safe to run again: existing
-- codes are kept as they are.
CREATE FUNCTION provision_charge_heads() RETURNS integer
    LANGUAGE plpgsql
AS $$
DECLARE
    v_tenant uuid := app_current_tenant();
    v_count  integer;
BEGIN
    IF v_tenant IS NULL THEN
        RAISE EXCEPTION 'provision_charge_heads: app.tenant_id is not set';
    END IF;

    INSERT INTO charge_head (tenant_id, code, name, calc_method, default_value, is_auto, edit_control, sort_order,
                             is_system, created_by)
    SELECT v_tenant, h.code, h.name, h.method, h.value, h.auto, h.edit, h.sort, h.code = 'FREIGHT', 'system'
      FROM (VALUES
            ('FREIGHT',         'Freight',               'RATE_CARD',          0,    true,  'INCREASE_ONLY', 10),
            ('GR_CHARGE',       'GR / Docket Charge',    'FIXED',              20,   true,  'LOCKED',        20),
            ('HAMALI',          'Hamali (Loading)',      'PER_PACKAGE',        0,    false, 'FREE',          30),
            ('DOOR_COLLECTION', 'Door Collection',       'FIXED',              0,    false, 'FREE',          40),
            ('DOOR_DELIVERY',   'Door Delivery',         'FIXED',              0,    false, 'FREE',          50),
            ('FOV',             'FOV / Risk Charge',     'PERCENT_OF_VALUE',   0,    false, 'FREE',          60),
            ('STATUTORY',       'Statutory Charge',      'FIXED',              0,    false, 'FREE',          70),
            ('ODA',             'ODA Charge',            'FIXED',              0,    false, 'FREE',          80),
            ('COD_DOD',         'COD / DOD Charge',      'FIXED',              0,    false, 'FREE',          90),
            ('TO_PAY',          'To-Pay Charge',         'FIXED',              0,    false, 'FREE',          100),
            ('DEMURRAGE',       'Demurrage',             'FIXED',              0,    false, 'FREE',          110)
           ) AS h(code, name, method, value, auto, edit, sort)
    ON CONFLICT DO NOTHING;

    SELECT count(*) INTO v_count FROM charge_head WHERE tenant_id = v_tenant AND deleted_at IS NULL;
    RETURN v_count;
END
$$;

-- ---------------------------------------------------------------------
-- Rate cards
-- ---------------------------------------------------------------------
CREATE SEQUENCE rate_card_code_seq;

CREATE TABLE rate_card (
    id                    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid        NOT NULL REFERENCES tenant (id),
    code                  text        NOT NULL DEFAULT 'RC' || lpad(nextval('rate_card_code_seq')::text, 6, '0'),
    name                  text        NOT NULL,
    party_id              uuid        REFERENCES party (id),        -- NULL = standard rates
    contract_ref          text,
    currency              char(3)     NOT NULL DEFAULT 'INR',
    valid_from            date        NOT NULL,
    valid_to              date,
    status                text        NOT NULL DEFAULT 'DRAFT'
                              CHECK (status IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'SUPERSEDED')),
    -- Client card without a matching line: fall back to standard rates, or block the booking
    fallback_to_standard  boolean     NOT NULL DEFAULT true,
    version               integer     NOT NULL DEFAULT 1,
    previous_card_id      uuid        REFERENCES rate_card (id),
    approved_by           text,
    approved_at           timestamptz,
    notes                 text,
    created_at            timestamptz NOT NULL DEFAULT now(),
    created_by            text,
    updated_at            timestamptz NOT NULL DEFAULT now(),
    deleted_at            timestamptz,
    deleted_by            text,
    delete_reason         text,
    CONSTRAINT rate_card_validity_chk CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

CREATE UNIQUE INDEX rate_card_code_uq ON rate_card (tenant_id, upper(code), version) WHERE deleted_at IS NULL;
CREATE INDEX rate_card_party_idx ON rate_card (tenant_id, party_id, status) WHERE deleted_at IS NULL;
SELECT tms_secure_tenant_table('rate_card');

CREATE TABLE rate_card_line (
    id                       uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                uuid           NOT NULL REFERENCES tenant (id),
    rate_card_id             uuid           NOT NULL REFERENCES rate_card (id) ON DELETE CASCADE,
    -- NULL in a matching column means "any"
    origin_city_id           uuid           REFERENCES city (id),
    destination_city_id      uuid           REFERENCES city (id),
    origin_location_id       uuid           REFERENCES location (id),
    destination_location_id  uuid           REFERENCES location (id),
    service                  text           CHECK (service IN ('FTL', 'PTL', 'EXPRESS', 'LOCAL', 'CONTAINER', 'ODC')),
    vehicle_type             text,
    commodity                text,
    payment_type             text           CHECK (payment_type IN ('PAID', 'TO_PAY', 'TBB')),
    rate_basis               text           NOT NULL CHECK (rate_basis IN (
                                 'PER_KG', 'PER_TONNE', 'PER_PACKAGE', 'PER_TRIP', 'PER_KM', 'PERCENT_OF_VALUE')),
    rate                     numeric(12, 4) NOT NULL CHECK (rate >= 0),
    min_charge               numeric(12, 2) NOT NULL DEFAULT 0 CHECK (min_charge >= 0),
    min_weight_kg            numeric(12, 3) NOT NULL DEFAULT 0 CHECK (min_weight_kg >= 0),
    -- Volumetric weight = volume (CFT) x factor (kg per CFT); 0 = not used
    volumetric_kg_per_cft    numeric(8, 3)  NOT NULL DEFAULT 0 CHECK (volumetric_kg_per_cft >= 0),
    transit_days             smallint       CHECK (transit_days BETWEEN 0 AND 60),
    sort_order               smallint       NOT NULL DEFAULT 0
);

CREATE INDEX rate_card_line_card_idx ON rate_card_line (rate_card_id);
CREATE INDEX rate_card_line_lane_idx ON rate_card_line (tenant_id, origin_city_id, destination_city_id);
SELECT tms_secure_tenant_table('rate_card_line');

-- Slabs: the whole chargeable quantity is priced at the rate of the slab it
-- falls in (kg for weight bases, packages for PER_PACKAGE).
CREATE TABLE rate_card_slab (
    id                 uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid           NOT NULL REFERENCES tenant (id),
    rate_card_line_id  uuid           NOT NULL REFERENCES rate_card_line (id) ON DELETE CASCADE,
    from_qty           numeric(12, 3) NOT NULL CHECK (from_qty >= 0),
    to_qty             numeric(12, 3),                         -- NULL = and above
    rate               numeric(12, 4) NOT NULL CHECK (rate >= 0),
    CONSTRAINT rate_card_slab_range_chk CHECK (to_qty IS NULL OR to_qty > from_qty)
);

CREATE INDEX rate_card_slab_line_idx ON rate_card_slab (rate_card_line_id, from_qty);
SELECT tms_secure_tenant_table('rate_card_slab');

-- Client-specific values for charge heads (override the head's default)
CREATE TABLE rate_card_charge (
    tenant_id       uuid           NOT NULL REFERENCES tenant (id),
    rate_card_id    uuid           NOT NULL REFERENCES rate_card (id) ON DELETE CASCADE,
    charge_head_id  uuid           NOT NULL REFERENCES charge_head (id),
    value           numeric(12, 4) NOT NULL CHECK (value >= 0),
    min_amount      numeric(12, 2) NOT NULL DEFAULT 0 CHECK (min_amount >= 0),
    is_auto         boolean        NOT NULL DEFAULT true,
    PRIMARY KEY (rate_card_id, charge_head_id)
);

SELECT tms_secure_tenant_table('rate_card_charge');
