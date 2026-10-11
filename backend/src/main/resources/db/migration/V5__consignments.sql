-- =====================================================================
-- V5: Consignment booking (Module 12: GR / bilty / LR; BOL and connote
-- in other countries). The screen label comes from the country pack.
--   * consignment: header, parties (with a snapshot of names and tax IDs
--     at booking), route, priced freight and charges, tax and totals
--   * consignment_package: goods lines
--   * consignment_charge: charges as booked
--   * consignment_event: status history (tracking)
-- Issued consignments are never deleted: they are cancelled with a reason.
-- =====================================================================

CREATE TABLE consignment (
    id                       uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                uuid           NOT NULL REFERENCES tenant (id),
    cn_no                    text           NOT NULL,
    cn_date                  date           NOT NULL,
    financial_year           text           NOT NULL,
    creation                 text           NOT NULL DEFAULT 'SYSTEM' CHECK (creation IN ('SYSTEM', 'MANUAL')),
    manual_book_no           text,
    booking_location_id      uuid           NOT NULL REFERENCES location (id),
    delivery_location_id     uuid           REFERENCES location (id),
    origin_city_id           uuid           NOT NULL REFERENCES city (id),
    destination_city_id      uuid           NOT NULL REFERENCES city (id),
    payment_type             text           NOT NULL CHECK (payment_type IN ('PAID', 'TO_PAY', 'TBB', 'FOC')),
    movement_type            text           NOT NULL DEFAULT 'DIRECT'
                                 CHECK (movement_type IN ('DIRECT', 'TRANSIT', 'CROSSING', 'LOCAL')),
    service                  text           NOT NULL DEFAULT 'PTL'
                                 CHECK (service IN ('FTL', 'PTL', 'EXPRESS', 'LOCAL', 'CONTAINER', 'ODC')),
    pickup_type              text           NOT NULL DEFAULT 'GODOWN' CHECK (pickup_type IN ('GODOWN', 'DOOR')),
    delivery_type            text           NOT NULL DEFAULT 'GODOWN' CHECK (delivery_type IN ('GODOWN', 'DOOR')),
    vehicle_type             text,
    expected_delivery_date   date,

    consignor_id             uuid           NOT NULL REFERENCES party (id),
    consignor_name           text           NOT NULL,
    consignor_tax_id         text,
    consignor_mobile         text,
    consignee_id             uuid           NOT NULL REFERENCES party (id),
    consignee_name           text           NOT NULL,
    consignee_tax_id         text,
    consignee_mobile         text,
    bill_to_id               uuid           NOT NULL REFERENCES party (id),
    bill_to_name             text           NOT NULL,

    total_packages           integer        NOT NULL DEFAULT 0 CHECK (total_packages >= 0),
    actual_weight_kg         numeric(12, 3) NOT NULL DEFAULT 0 CHECK (actual_weight_kg >= 0),
    volume_cft               numeric(12, 3) NOT NULL DEFAULT 0 CHECK (volume_cft >= 0),
    chargeable_weight_kg     numeric(12, 3) NOT NULL DEFAULT 0 CHECK (chargeable_weight_kg >= 0),
    declared_value           numeric(14, 2) NOT NULL DEFAULT 0 CHECK (declared_value >= 0),
    invoice_numbers          text[]         NOT NULL DEFAULT '{}',
    eway_bill_no             text,
    eway_bill_valid_until    date,
    risk                     text           NOT NULL DEFAULT 'OWNER' CHECK (risk IN ('OWNER', 'CARRIER')),
    private_marks            text,
    instructions             text,

    -- Pricing as applied at booking
    rate_source              text           NOT NULL CHECK (rate_source IN ('CLIENT_CARD', 'STANDARD', 'NONE')),
    rate_card_id             uuid           REFERENCES rate_card (id),
    rate_card_line_id        uuid,
    rate_basis               text,
    rate                     numeric(12, 4),
    quoted_freight           numeric(14, 2),
    freight                  numeric(14, 2) NOT NULL CHECK (freight >= 0),
    charges_total            numeric(14, 2) NOT NULL DEFAULT 0 CHECK (charges_total >= 0),
    discount                 numeric(14, 2) NOT NULL DEFAULT 0 CHECK (discount >= 0),
    taxable_amount           numeric(14, 2) NOT NULL DEFAULT 0,
    tax_paid_by              text           NOT NULL DEFAULT 'RCM' CHECK (tax_paid_by IN ('RCM', 'TRANSPORTER', 'EXEMPT')),
    tax_rate                 numeric(5, 2)  NOT NULL DEFAULT 0 CHECK (tax_rate BETWEEN 0 AND 40),
    tax_amount               numeric(14, 2) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
    total                    numeric(14, 2) NOT NULL DEFAULT 0,
    currency                 char(3)        NOT NULL,
    override_reason          text,                       -- below-rate freight, discount or locked charge change
    approved_by              text,

    status                   text           NOT NULL DEFAULT 'BOOKED' CHECK (status IN (
                                 'BOOKED', 'LOADED', 'IN_TRANSIT', 'ARRIVED', 'OUT_FOR_DELIVERY', 'DELIVERED',
                                 'UNDELIVERED', 'RETURNED', 'CANCELLED')),
    cancelled_at             timestamptz,
    cancelled_by             text,
    cancel_reason            text,
    created_at               timestamptz    NOT NULL DEFAULT now(),
    created_by               text,
    updated_at               timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT consignment_cancel_chk CHECK (status <> 'CANCELLED' OR cancel_reason IS NOT NULL)
);

CREATE UNIQUE INDEX consignment_no_uq ON consignment (tenant_id, upper(cn_no));
CREATE INDEX consignment_date_idx ON consignment (tenant_id, cn_date DESC);
CREATE INDEX consignment_location_idx ON consignment (tenant_id, booking_location_id, cn_date DESC);
CREATE INDEX consignment_consignor_idx ON consignment (tenant_id, consignor_id);
CREATE INDEX consignment_consignee_idx ON consignment (tenant_id, consignee_id);
CREATE INDEX consignment_bill_to_idx ON consignment (tenant_id, bill_to_id);
CREATE INDEX consignment_eway_idx ON consignment (tenant_id, eway_bill_no) WHERE eway_bill_no IS NOT NULL;
SELECT tms_secure_tenant_table('consignment');

CREATE TABLE consignment_package (
    id               uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid           NOT NULL REFERENCES tenant (id),
    consignment_id   uuid           NOT NULL REFERENCES consignment (id) ON DELETE CASCADE,
    packages         integer        NOT NULL CHECK (packages > 0),
    package_type     text           NOT NULL,
    said_to_contain  text           NOT NULL,
    hsn_code         text,
    actual_weight_kg numeric(12, 3) NOT NULL DEFAULT 0 CHECK (actual_weight_kg >= 0),
    volume_cft       numeric(12, 3) NOT NULL DEFAULT 0 CHECK (volume_cft >= 0),
    value            numeric(14, 2) NOT NULL DEFAULT 0 CHECK (value >= 0),
    sort_order       smallint       NOT NULL DEFAULT 0
);

CREATE INDEX consignment_package_cn_idx ON consignment_package (consignment_id);
SELECT tms_secure_tenant_table('consignment_package');

CREATE TABLE consignment_charge (
    id               uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid           NOT NULL REFERENCES tenant (id),
    consignment_id   uuid           NOT NULL REFERENCES consignment (id) ON DELETE CASCADE,
    charge_head_id   uuid           NOT NULL REFERENCES charge_head (id),
    code             text           NOT NULL,
    name             text           NOT NULL,
    quoted_amount    numeric(14, 2),
    amount           numeric(14, 2) NOT NULL CHECK (amount >= 0),
    taxable          boolean        NOT NULL DEFAULT true,
    sort_order       smallint       NOT NULL DEFAULT 0
);

CREATE INDEX consignment_charge_cn_idx ON consignment_charge (consignment_id);
SELECT tms_secure_tenant_table('consignment_charge');

CREATE TABLE consignment_event (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenant (id),
    consignment_id   uuid        NOT NULL REFERENCES consignment (id) ON DELETE CASCADE,
    status           text        NOT NULL,
    location_id      uuid        REFERENCES location (id),
    note             text,
    occurred_at      timestamptz NOT NULL DEFAULT now(),
    user_id          text
);

CREATE INDEX consignment_event_cn_idx ON consignment_event (consignment_id, occurred_at);
SELECT tms_secure_tenant_table('consignment_event');
