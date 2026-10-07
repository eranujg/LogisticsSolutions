-- =====================================================================
-- V3: Master data
--   country packs, states/provinces, company, tax registrations,
--   locations, cities and city service roles, permissions, roles,
--   users, parties (consignors / consignees / bill-to)
-- Global reference tables are read-only for the application.
-- Tenant tables use tms_secure_tenant_table (RLS + audit + updated_at).
-- =====================================================================

-- ---------------------------------------------------------------------
-- Country packs (global reference data; defaults a company can override)
-- ---------------------------------------------------------------------
CREATE TABLE country_pack (
    code                  char(2)  PRIMARY KEY,
    name                  text     NOT NULL,
    currency_code         char(3)  NOT NULL,
    distance_unit         text     NOT NULL CHECK (distance_unit IN ('KM', 'MILE')),
    weight_unit           text     NOT NULL CHECK (weight_unit IN ('KG', 'LB')),
    fuel_unit             text     NOT NULL CHECK (fuel_unit IN ('LITRE', 'GALLON')),
    fy_start_month        smallint NOT NULL CHECK (fy_start_month BETWEEN 1 AND 12),
    date_format           text     NOT NULL,
    number_grouping       text     NOT NULL CHECK (number_grouping IN ('INDIAN', 'INTERNATIONAL')),
    consignment_note_name text     NOT NULL,
    company_tax_id_types  text[]   NOT NULL,
    party_tax_id_types    text[]   NOT NULL,
    ownership_types       text[]   NOT NULL,
    region_label          text     NOT NULL
);

INSERT INTO country_pack VALUES
('IN', 'India', 'INR', 'KM', 'KG', 'LITRE', 4, 'dd/MM/yyyy', 'INDIAN', 'GR / Bilty',
 ARRAY['GSTIN', 'PAN', 'TAN', 'UDYAM', 'CIN'],
 ARRAY['GSTIN', 'PAN', 'TAN'],
 ARRAY['SOLE_PROPRIETORSHIP', 'PARTNERSHIP', 'LLP', 'PRIVATE_LIMITED', 'PUBLIC_LIMITED', 'OPC', 'HUF', 'COOPERATIVE'],
 'State'),
('CA', 'Canada', 'CAD', 'KM', 'KG', 'LITRE', 1, 'yyyy-MM-dd', 'INTERNATIONAL', 'Bill of Lading',
 ARRAY['BN', 'GST_HST', 'QST', 'CVOR', 'NSC', 'CBSA_CARRIER_CODE'],
 ARRAY['BN', 'GST_HST', 'QST'],
 ARRAY['SOLE_PROPRIETORSHIP', 'GENERAL_PARTNERSHIP', 'LIMITED_PARTNERSHIP', 'CORPORATION', 'COOPERATIVE'],
 'Province'),
('US', 'United States', 'USD', 'MILE', 'LB', 'GALLON', 1, 'MM/dd/yyyy', 'INTERNATIONAL', 'Bill of Lading',
 ARRAY['EIN', 'USDOT', 'MC', 'UCR', 'IFTA'],
 ARRAY['EIN'],
 ARRAY['SOLE_PROPRIETORSHIP', 'GENERAL_PARTNERSHIP', 'LP', 'LLP', 'LLC', 'S_CORP', 'C_CORP'],
 'State'),
('AU', 'Australia', 'AUD', 'KM', 'KG', 'LITRE', 7, 'dd/MM/yyyy', 'INTERNATIONAL', 'Consignment Note',
 ARRAY['ABN', 'ACN', 'TFN', 'NHVAS'],
 ARRAY['ABN', 'ACN'],
 ARRAY['SOLE_TRADER', 'PARTNERSHIP', 'PTY_LTD', 'PUBLIC_COMPANY', 'TRUST', 'COOPERATIVE'],
 'State');

REVOKE ALL ON country_pack FROM tms_app;
GRANT SELECT ON country_pack TO tms_app;

-- ---------------------------------------------------------------------
-- States / provinces (global; gst_state_code is India's GST state code)
-- ---------------------------------------------------------------------
CREATE TABLE state_province (
    country_code   char(2) NOT NULL REFERENCES country_pack (code),
    code           text    NOT NULL,
    name           text    NOT NULL,
    kind           text    NOT NULL DEFAULT 'STATE' CHECK (kind IN ('STATE', 'UNION_TERRITORY', 'PROVINCE', 'TERRITORY', 'DISTRICT')),
    gst_state_code char(2),
    PRIMARY KEY (country_code, code)
);

INSERT INTO state_province (country_code, code, name, kind, gst_state_code) VALUES
('IN','JK','Jammu and Kashmir','UNION_TERRITORY','01'),
('IN','HP','Himachal Pradesh','STATE','02'),
('IN','PB','Punjab','STATE','03'),
('IN','CH','Chandigarh','UNION_TERRITORY','04'),
('IN','UK','Uttarakhand','STATE','05'),
('IN','HR','Haryana','STATE','06'),
('IN','DL','Delhi','UNION_TERRITORY','07'),
('IN','RJ','Rajasthan','STATE','08'),
('IN','UP','Uttar Pradesh','STATE','09'),
('IN','BR','Bihar','STATE','10'),
('IN','SK','Sikkim','STATE','11'),
('IN','AR','Arunachal Pradesh','STATE','12'),
('IN','NL','Nagaland','STATE','13'),
('IN','MN','Manipur','STATE','14'),
('IN','MZ','Mizoram','STATE','15'),
('IN','TR','Tripura','STATE','16'),
('IN','ML','Meghalaya','STATE','17'),
('IN','AS','Assam','STATE','18'),
('IN','WB','West Bengal','STATE','19'),
('IN','JH','Jharkhand','STATE','20'),
('IN','OD','Odisha','STATE','21'),
('IN','CG','Chhattisgarh','STATE','22'),
('IN','MP','Madhya Pradesh','STATE','23'),
('IN','GJ','Gujarat','STATE','24'),
('IN','DH','Dadra and Nagar Haveli and Daman and Diu','UNION_TERRITORY','26'),
('IN','MH','Maharashtra','STATE','27'),
('IN','KA','Karnataka','STATE','29'),
('IN','GA','Goa','STATE','30'),
('IN','LD','Lakshadweep','UNION_TERRITORY','31'),
('IN','KL','Kerala','STATE','32'),
('IN','TN','Tamil Nadu','STATE','33'),
('IN','PY','Puducherry','UNION_TERRITORY','34'),
('IN','AN','Andaman and Nicobar Islands','UNION_TERRITORY','35'),
('IN','TG','Telangana','STATE','36'),
('IN','AP','Andhra Pradesh','STATE','37'),
('IN','LA','Ladakh','UNION_TERRITORY','38'),
('CA','AB','Alberta','PROVINCE',NULL),
('CA','BC','British Columbia','PROVINCE',NULL),
('CA','MB','Manitoba','PROVINCE',NULL),
('CA','NB','New Brunswick','PROVINCE',NULL),
('CA','NL','Newfoundland and Labrador','PROVINCE',NULL),
('CA','NS','Nova Scotia','PROVINCE',NULL),
('CA','ON','Ontario','PROVINCE',NULL),
('CA','PE','Prince Edward Island','PROVINCE',NULL),
('CA','QC','Quebec','PROVINCE',NULL),
('CA','SK','Saskatchewan','PROVINCE',NULL),
('CA','NT','Northwest Territories','TERRITORY',NULL),
('CA','NU','Nunavut','TERRITORY',NULL),
('CA','YT','Yukon','TERRITORY',NULL),
('AU','ACT','Australian Capital Territory','TERRITORY',NULL),
('AU','NSW','New South Wales','STATE',NULL),
('AU','NT','Northern Territory','TERRITORY',NULL),
('AU','QLD','Queensland','STATE',NULL),
('AU','SA','South Australia','STATE',NULL),
('AU','TAS','Tasmania','STATE',NULL),
('AU','VIC','Victoria','STATE',NULL),
('AU','WA','Western Australia','STATE',NULL),
('US','AL','Alabama','STATE',NULL),('US','AK','Alaska','STATE',NULL),('US','AZ','Arizona','STATE',NULL),
('US','AR','Arkansas','STATE',NULL),('US','CA','California','STATE',NULL),('US','CO','Colorado','STATE',NULL),
('US','CT','Connecticut','STATE',NULL),('US','DE','Delaware','STATE',NULL),('US','DC','District of Columbia','DISTRICT',NULL),
('US','FL','Florida','STATE',NULL),('US','GA','Georgia','STATE',NULL),('US','HI','Hawaii','STATE',NULL),
('US','ID','Idaho','STATE',NULL),('US','IL','Illinois','STATE',NULL),('US','IN','Indiana','STATE',NULL),
('US','IA','Iowa','STATE',NULL),('US','KS','Kansas','STATE',NULL),('US','KY','Kentucky','STATE',NULL),
('US','LA','Louisiana','STATE',NULL),('US','ME','Maine','STATE',NULL),('US','MD','Maryland','STATE',NULL),
('US','MA','Massachusetts','STATE',NULL),('US','MI','Michigan','STATE',NULL),('US','MN','Minnesota','STATE',NULL),
('US','MS','Mississippi','STATE',NULL),('US','MO','Missouri','STATE',NULL),('US','MT','Montana','STATE',NULL),
('US','NE','Nebraska','STATE',NULL),('US','NV','Nevada','STATE',NULL),('US','NH','New Hampshire','STATE',NULL),
('US','NJ','New Jersey','STATE',NULL),('US','NM','New Mexico','STATE',NULL),('US','NY','New York','STATE',NULL),
('US','NC','North Carolina','STATE',NULL),('US','ND','North Dakota','STATE',NULL),('US','OH','Ohio','STATE',NULL),
('US','OK','Oklahoma','STATE',NULL),('US','OR','Oregon','STATE',NULL),('US','PA','Pennsylvania','STATE',NULL),
('US','RI','Rhode Island','STATE',NULL),('US','SC','South Carolina','STATE',NULL),('US','SD','South Dakota','STATE',NULL),
('US','TN','Tennessee','STATE',NULL),('US','TX','Texas','STATE',NULL),('US','UT','Utah','STATE',NULL),
('US','VT','Vermont','STATE',NULL),('US','VA','Virginia','STATE',NULL),('US','WA','Washington','STATE',NULL),
('US','WV','West Virginia','STATE',NULL),('US','WI','Wisconsin','STATE',NULL),('US','WY','Wyoming','STATE',NULL);

REVOKE ALL ON state_province FROM tms_app;
GRANT SELECT ON state_province TO tms_app;

-- ---------------------------------------------------------------------
-- Company
-- ---------------------------------------------------------------------
CREATE TABLE company (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenant (id),
    legal_name      text        NOT NULL,
    trade_name      text,
    country_code    char(2)     NOT NULL REFERENCES country_pack (code),
    state_code      text,
    business_types  text[]      NOT NULL,
    ownership_type  text        NOT NULL,
    base_currency   char(3)     NOT NULL,
    fy_start_month  smallint    NOT NULL CHECK (fy_start_month BETWEEN 1 AND 12),
    email           text,
    phone           text,
    website         text,
    address_line1   text,
    address_line2   text,
    city_name       text,
    postal_code     text,
    status          text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      text,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz,
    deleted_by      text,
    delete_reason   text,
    CONSTRAINT company_state_fk FOREIGN KEY (country_code, state_code) REFERENCES state_province (country_code, code),
    CONSTRAINT company_business_types_chk CHECK (
        cardinality(business_types) > 0
        AND business_types <@ ARRAY['FLEET_OWNER', 'TRANSPORTER', 'BROKER', '3PL', 'SHIPPER_OWN_FLEET', 'COURIER']::text[])
);

CREATE INDEX company_tenant_idx ON company (tenant_id) WHERE deleted_at IS NULL;
SELECT tms_secure_tenant_table('company');

-- ---------------------------------------------------------------------
-- Tax registrations for companies, locations and parties
-- (GSTIN per state, PAN, BN, EIN, ABN, ...)
-- ---------------------------------------------------------------------
CREATE TABLE tax_registration (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenant (id),
    owner_type   text        NOT NULL CHECK (owner_type IN ('COMPANY', 'LOCATION', 'PARTY')),
    owner_id     uuid        NOT NULL,
    tax_type     text        NOT NULL,
    number       text        NOT NULL,
    state_code   text,
    valid_from   date,
    valid_to     date,
    verified     boolean     NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tax_registration_uq UNIQUE (tenant_id, owner_type, owner_id, tax_type, number)
);

CREATE INDEX tax_registration_owner_idx  ON tax_registration (tenant_id, owner_type, owner_id);
CREATE INDEX tax_registration_number_idx ON tax_registration (tenant_id, tax_type, upper(number));
SELECT tms_secure_tenant_table('tax_registration');

-- ---------------------------------------------------------------------
-- Cities (global seed + tenant additions)
-- ---------------------------------------------------------------------
CREATE TABLE city (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        REFERENCES tenant (id),          -- NULL = global
    country_code    char(2)     NOT NULL REFERENCES country_pack (code),
    state_code      text        NOT NULL,
    name            text        NOT NULL,
    aliases         text[]      NOT NULL DEFAULT '{}',
    district        text,
    classification  text        CHECK (classification IN ('METRO', 'TIER_1', 'TIER_2', 'TIER_3', 'RURAL')),
    postal_codes    text[]      NOT NULL DEFAULT '{}',
    latitude        numeric(9, 6),
    longitude       numeric(9, 6),
    status          text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'INACTIVE')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT city_state_fk FOREIGN KEY (country_code, state_code) REFERENCES state_province (country_code, code)
);

CREATE UNIQUE INDEX city_name_uq ON city (country_code, state_code, lower(name), tenant_id) NULLS NOT DISTINCT;
CREATE INDEX city_search_idx ON city (country_code, lower(name));

-- Seed before RLS is enabled (works for non-superuser migration users too).
INSERT INTO city (country_code, state_code, name, aliases, classification, latitude, longitude) VALUES
('IN','DL','New Delhi','{Delhi}','METRO',28.613900,77.209000),
('IN','MH','Mumbai','{Bombay}','METRO',19.076000,72.877700),
('IN','WB','Kolkata','{Calcutta}','METRO',22.572600,88.363900),
('IN','TN','Chennai','{Madras}','METRO',13.082700,80.270700),
('IN','KA','Bengaluru','{Bangalore}','METRO',12.971600,77.594600),
('IN','TG','Hyderabad','{}','METRO',17.385000,78.486700),
('IN','GJ','Ahmedabad','{}','METRO',23.022500,72.571400),
('IN','MH','Pune','{Poona}','METRO',18.520400,73.856700),
('IN','GJ','Surat','{}','TIER_1',21.170200,72.831100),
('IN','RJ','Jaipur','{}','TIER_1',26.912400,75.787300),
('IN','UP','Lucknow','{}','TIER_1',26.846700,80.946200),
('IN','UP','Kanpur','{}','TIER_1',26.449900,80.331900),
('IN','MH','Nagpur','{}','TIER_1',21.145800,79.088200),
('IN','MP','Indore','{}','TIER_1',22.719600,75.857700),
('IN','PB','Ludhiana','{}','TIER_1',30.901000,75.857300),
('IN','CH','Chandigarh','{}','TIER_1',30.733300,76.779400),
('IN','HR','Gurugram','{Gurgaon}','TIER_1',28.459500,77.026600),
('IN','HR','Panchkula','{}','TIER_2',30.694100,76.860600),
('IN','AS','Guwahati','{}','TIER_1',26.144500,91.736200),
('IN','PB','Amritsar','{}','TIER_2',31.634000,74.872300);

REVOKE ALL ON city FROM tms_app;
GRANT SELECT, INSERT, UPDATE ON city TO tms_app;
ALTER TABLE city ENABLE ROW LEVEL SECURITY;
ALTER TABLE city FORCE ROW LEVEL SECURITY;
-- Everyone reads global cities; tenants add and edit only their own.
CREATE POLICY city_read  ON city FOR SELECT USING (tenant_id IS NULL OR tenant_id = app_current_tenant());
CREATE POLICY city_write ON city FOR INSERT WITH CHECK (tenant_id = app_current_tenant());
CREATE POLICY city_edit  ON city FOR UPDATE USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
CREATE TRIGGER city_audit AFTER INSERT OR UPDATE OR DELETE ON city FOR EACH ROW EXECUTE FUNCTION audit_row();
CREATE TRIGGER city_updated_at BEFORE UPDATE ON city FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------
-- Locations (offices, hubs, yards, workshops, residences ...)
-- ---------------------------------------------------------------------
CREATE TABLE location (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid        NOT NULL REFERENCES tenant (id),
    company_id         uuid        NOT NULL REFERENCES company (id),
    code               text        NOT NULL,
    name               text        NOT NULL,
    types              text[]      NOT NULL,
    parent_id          uuid        REFERENCES location (id),
    status             text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'TEMPORARILY_CLOSED', 'CLOSED')),
    tenure             text        CHECK (tenure IN ('OWNED', 'RENTED', 'LEASED', 'FRANCHISE', 'SHARED')),
    address_line1      text,
    address_line2      text,
    city_id            uuid        REFERENCES city (id),
    state_code         text,
    postal_code        text,
    latitude           numeric(9, 6),
    longitude          numeric(9, 6),
    geofence_radius_m  integer     CHECK (geofence_radius_m BETWEEN 10 AND 50000),
    manager_name       text,
    phone              text,
    email              text,
    opened_on          date,
    cost_center_code   text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    created_by         text,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    deleted_at         timestamptz,
    deleted_by         text,
    delete_reason      text,
    CONSTRAINT location_not_own_parent CHECK (parent_id IS DISTINCT FROM id),
    CONSTRAINT location_types_chk CHECK (
        cardinality(types) > 0
        AND types <@ ARRAY[
            'REGISTERED_OFFICE', 'HEAD_OFFICE', 'REGIONAL_OFFICE', 'BRANCH_OFFICE', 'ACCOUNTS_OFFICE', 'SALES_OFFICE',
            'BOOKING_OFFICE', 'DELIVERY_OFFICE', 'TRANSIT_HUB', 'DISPATCH_CENTER', 'FRANCHISE_AGENCY', 'COLLECTION_POINT',
            'IN_PLANT_OFFICE', 'BORDER_OFFICE', 'WAREHOUSE', 'COLD_STORAGE', 'CONTAINER_YARD',
            'TRUCK_CARE_CENTER', 'REPAIR_HUB', 'SPARE_PARTS_STORE', 'TYRE_SHOP', 'FUEL_STATION', 'EV_CHARGING_DEPOT',
            'WEIGHBRIDGE', 'PARKING_YARD', 'SCRAP_YARD',
            'LABOUR_RESIDENCE', 'LABOUR_KITCHEN', 'STAFF_QUARTERS', 'DRIVER_REST_HOUSE', 'TRAINING_CENTER']::text[])
);

CREATE UNIQUE INDEX location_code_uq ON location (tenant_id, upper(code)) WHERE deleted_at IS NULL;
CREATE INDEX location_types_idx ON location USING gin (types);
SELECT tms_secure_tenant_table('location');

-- ---------------------------------------------------------------------
-- City service roles per tenant: booking / delivery / transit and serving office
-- ---------------------------------------------------------------------
CREATE TABLE city_service (
    id                    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid        NOT NULL REFERENCES tenant (id),
    city_id               uuid        NOT NULL REFERENCES city (id),
    is_booking            boolean     NOT NULL DEFAULT false,
    is_delivery           boolean     NOT NULL DEFAULT false,
    is_transit            boolean     NOT NULL DEFAULT false,
    booking_location_id   uuid        REFERENCES location (id),
    delivery_location_id  uuid        REFERENCES location (id),
    transit_location_id   uuid        REFERENCES location (id),
    door_pickup           boolean     NOT NULL DEFAULT false,
    door_delivery         boolean     NOT NULL DEFAULT false,
    oda                   boolean     NOT NULL DEFAULT false,
    oda_extra_days        smallint    NOT NULL DEFAULT 0 CHECK (oda_extra_days >= 0),
    booking_cutoff        time,
    status                text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    suspended_reason      text,
    notes                 text,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    deleted_at            timestamptz,
    deleted_by            text,
    delete_reason         text,
    CONSTRAINT city_service_role_chk CHECK (is_booking OR is_delivery OR is_transit)
);

CREATE UNIQUE INDEX city_service_city_uq ON city_service (tenant_id, city_id) WHERE deleted_at IS NULL;
SELECT tms_secure_tenant_table('city_service');

-- ---------------------------------------------------------------------
-- Permissions (global catalogue), roles, users
-- ---------------------------------------------------------------------
CREATE TABLE permission (
    code        text PRIMARY KEY,
    module      text NOT NULL,
    action      text NOT NULL CHECK (action IN ('VIEW', 'CREATE', 'EDIT', 'DELETE', 'CANCEL', 'APPROVE', 'PRINT', 'EXPORT')),
    sensitive   boolean NOT NULL DEFAULT false
);

INSERT INTO permission (code, module, action, sensitive)
SELECT m.module || '.' || lower(a.action), m.module, a.action, m.sensitive
  FROM (VALUES
        ('company', true), ('location', false), ('user', true), ('role', true), ('city', false),
        ('party', false), ('number_series', true), ('audit', true), ('recycle_bin', true),
        ('quotation', false), ('rate_card', true), ('gr', false), ('challan', false), ('receipt', false),
        ('delivery', false), ('pod', false), ('vehicle', false), ('lorry_hire', true), ('billing', false),
        ('accounts', true), ('payroll', true), ('report', false)) AS m(module, sensitive)
 CROSS JOIN (VALUES ('VIEW'), ('CREATE'), ('EDIT'), ('DELETE'), ('CANCEL'), ('APPROVE'), ('PRINT'), ('EXPORT')) AS a(action);

REVOKE ALL ON permission FROM tms_app;
GRANT SELECT ON permission TO tms_app;

CREATE TABLE role (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    code          text        NOT NULL,
    name          text        NOT NULL,
    description   text,
    is_system     boolean     NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    deleted_at    timestamptz,
    deleted_by    text,
    delete_reason text
);

CREATE UNIQUE INDEX role_code_uq ON role (tenant_id, upper(code)) WHERE deleted_at IS NULL;
SELECT tms_secure_tenant_table('role');

CREATE TABLE role_permission (
    tenant_id        uuid NOT NULL REFERENCES tenant (id),
    role_id          uuid NOT NULL REFERENCES role (id) ON DELETE CASCADE,
    permission_code  text NOT NULL REFERENCES permission (code),
    scope            text NOT NULL DEFAULT 'COMPANY' CHECK (scope IN ('COMPANY', 'REGION', 'LOCATIONS', 'OWN')),
    PRIMARY KEY (role_id, permission_code)
);

SELECT tms_secure_tenant_table('role_permission');

CREATE TABLE app_user (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid        NOT NULL REFERENCES tenant (id),
    user_type          text        NOT NULL CHECK (user_type IN (
                           'STAFF', 'DRIVER', 'CLIENT', 'CONSIGNEE', 'BOOKING_AGENT', 'DELIVERY_AGENT',
                           'CROSSING_AGENT', 'VEHICLE_OWNER', 'BROKER', 'VENDOR', 'AUDITOR', 'LABOUR_CONTRACTOR')),
    full_name          text        NOT NULL,
    email              citext,
    mobile             text,
    external_subject   text        UNIQUE,      -- identity provider subject (Keycloak / Cognito)
    status             text        NOT NULL DEFAULT 'INVITED' CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    home_location_id   uuid        REFERENCES location (id),
    party_id           uuid,                    -- portal users: the client/agent they belong to
    preferred_language text        NOT NULL DEFAULT 'en' CHECK (preferred_language IN ('en', 'hi', 'pa', 'fr')),
    access_expires_at  timestamptz,             -- e.g. time-limited CA / auditor access
    last_login_at      timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    created_by         text,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    deleted_at         timestamptz,
    deleted_by         text,
    delete_reason      text,
    CONSTRAINT app_user_contact_chk CHECK (email IS NOT NULL OR mobile IS NOT NULL)
);

CREATE UNIQUE INDEX app_user_email_uq  ON app_user (tenant_id, email)  WHERE deleted_at IS NULL AND email IS NOT NULL;
CREATE UNIQUE INDEX app_user_mobile_uq ON app_user (tenant_id, mobile) WHERE deleted_at IS NULL AND mobile IS NOT NULL;
SELECT tms_secure_tenant_table('app_user');

CREATE TABLE user_role (
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    user_id    uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role_id    uuid NOT NULL REFERENCES role (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);
SELECT tms_secure_tenant_table('user_role');

CREATE TABLE user_location (
    tenant_id    uuid NOT NULL REFERENCES tenant (id),
    user_id      uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    location_id  uuid NOT NULL REFERENCES location (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, location_id)
);
SELECT tms_secure_tenant_table('user_location');

-- ---------------------------------------------------------------------
-- Default roles for a new tenant (runs for app_current_tenant())
-- ---------------------------------------------------------------------
CREATE FUNCTION provision_tenant_defaults() RETURNS integer
    LANGUAGE plpgsql
AS $$
DECLARE
    v_tenant uuid := app_current_tenant();
    v_count  integer;
BEGIN
    IF v_tenant IS NULL THEN
        RAISE EXCEPTION 'provision_tenant_defaults: app.tenant_id is not set';
    END IF;

    INSERT INTO role (tenant_id, code, name, description, is_system)
    VALUES
        (v_tenant, 'OWNER',          'Owner / Partner',    'Full access to everything',                       true),
        (v_tenant, 'ADMIN',          'Administrator',      'Full access except ownership records',            true),
        (v_tenant, 'BRANCH_MANAGER', 'Branch Manager',     'Runs operations at assigned locations',           true),
        (v_tenant, 'ACCOUNTANT',     'Accountant',         'Receipts, billing and accounts',                  true),
        (v_tenant, 'BOOKING_CLERK',  'Booking Clerk',      'Books GRs and collects payments',                 true),
        (v_tenant, 'DELIVERY_CLERK', 'Delivery Clerk',     'Unloading, delivery and PODs',                    true),
        (v_tenant, 'DRIVER',         'Driver',             'Own trips, deliveries and PODs',                  true),
        (v_tenant, 'AUDITOR',        'CA / Auditor',       'Read-only access with export, time limited',      true),
        (v_tenant, 'CLIENT_USER',    'Client Portal User', 'Own consignments, PODs and invoices',             true);

    -- OWNER and ADMIN: everything
    INSERT INTO role_permission (tenant_id, role_id, permission_code, scope)
    SELECT v_tenant, r.id, p.code, 'COMPANY'
      FROM role r CROSS JOIN permission p
     WHERE r.tenant_id = v_tenant AND r.code = 'OWNER';

    INSERT INTO role_permission (tenant_id, role_id, permission_code, scope)
    SELECT v_tenant, r.id, p.code, 'COMPANY'
      FROM role r CROSS JOIN permission p
     WHERE r.tenant_id = v_tenant AND r.code = 'ADMIN' AND p.module <> 'company';

    -- AUDITOR: view and export everything
    INSERT INTO role_permission (tenant_id, role_id, permission_code, scope)
    SELECT v_tenant, r.id, p.code, 'COMPANY'
      FROM role r CROSS JOIN permission p
     WHERE r.tenant_id = v_tenant AND r.code = 'AUDITOR' AND p.action IN ('VIEW', 'EXPORT');

    -- Remaining roles from a compact module/action/scope list
    INSERT INTO role_permission (tenant_id, role_id, permission_code, scope)
    SELECT v_tenant, r.id, m.module || '.' || lower(a.action), m.scope
      FROM (VALUES
            ('BRANCH_MANAGER', 'gr',        'VIEW,CREATE,EDIT,CANCEL,APPROVE,PRINT,EXPORT', 'LOCATIONS'),
            ('BRANCH_MANAGER', 'challan',   'VIEW,CREATE,EDIT,CANCEL,APPROVE,PRINT,EXPORT', 'LOCATIONS'),
            ('BRANCH_MANAGER', 'receipt',   'VIEW,CREATE,CANCEL,APPROVE,PRINT,EXPORT',      'LOCATIONS'),
            ('BRANCH_MANAGER', 'delivery',  'VIEW,CREATE,EDIT,APPROVE,PRINT,EXPORT',        'LOCATIONS'),
            ('BRANCH_MANAGER', 'pod',       'VIEW,CREATE,EDIT,APPROVE,EXPORT',              'LOCATIONS'),
            ('BRANCH_MANAGER', 'party',     'VIEW,CREATE,EDIT,EXPORT',                      'LOCATIONS'),
            ('BRANCH_MANAGER', 'quotation', 'VIEW,CREATE,EDIT,APPROVE,PRINT',               'LOCATIONS'),
            ('BRANCH_MANAGER', 'rate_card', 'VIEW',                                         'LOCATIONS'),
            ('BRANCH_MANAGER', 'city',      'VIEW,CREATE,EDIT',                             'COMPANY'),
            ('BRANCH_MANAGER', 'location',  'VIEW',                                         'COMPANY'),
            ('BRANCH_MANAGER', 'vehicle',   'VIEW',                                         'LOCATIONS'),
            ('BRANCH_MANAGER', 'report',    'VIEW,EXPORT',                                  'LOCATIONS'),
            ('ACCOUNTANT',     'receipt',   'VIEW,CREATE,EDIT,CANCEL,PRINT,EXPORT',         'COMPANY'),
            ('ACCOUNTANT',     'billing',   'VIEW,CREATE,EDIT,CANCEL,PRINT,EXPORT',         'COMPANY'),
            ('ACCOUNTANT',     'accounts',  'VIEW,CREATE,EDIT,CANCEL,PRINT,EXPORT',         'COMPANY'),
            ('ACCOUNTANT',     'lorry_hire','VIEW,EDIT,EXPORT',                             'COMPANY'),
            ('ACCOUNTANT',     'party',     'VIEW,EDIT,EXPORT',                             'COMPANY'),
            ('ACCOUNTANT',     'gr',        'VIEW,EXPORT',                                  'COMPANY'),
            ('ACCOUNTANT',     'report',    'VIEW,EXPORT',                                  'COMPANY'),
            ('BOOKING_CLERK',  'gr',        'VIEW,CREATE,EDIT,PRINT',                       'LOCATIONS'),
            ('BOOKING_CLERK',  'receipt',   'VIEW,CREATE,PRINT',                            'LOCATIONS'),
            ('BOOKING_CLERK',  'party',     'VIEW,CREATE',                                  'LOCATIONS'),
            ('BOOKING_CLERK',  'quotation', 'VIEW,CREATE',                                  'LOCATIONS'),
            ('BOOKING_CLERK',  'rate_card', 'VIEW',                                         'LOCATIONS'),
            ('BOOKING_CLERK',  'city',      'VIEW',                                         'COMPANY'),
            ('DELIVERY_CLERK', 'gr',        'VIEW',                                         'LOCATIONS'),
            ('DELIVERY_CLERK', 'challan',   'VIEW,CREATE,PRINT',                            'LOCATIONS'),
            ('DELIVERY_CLERK', 'delivery',  'VIEW,CREATE,PRINT',                            'LOCATIONS'),
            ('DELIVERY_CLERK', 'pod',       'VIEW,CREATE',                                  'LOCATIONS'),
            ('DELIVERY_CLERK', 'receipt',   'VIEW,CREATE,PRINT',                            'LOCATIONS'),
            ('DELIVERY_CLERK', 'party',     'VIEW',                                         'LOCATIONS'),
            ('DRIVER',         'challan',   'VIEW',                                         'OWN'),
            ('DRIVER',         'delivery',  'VIEW,CREATE',                                  'OWN'),
            ('DRIVER',         'pod',       'VIEW,CREATE',                                  'OWN'),
            ('CLIENT_USER',    'gr',        'VIEW,PRINT',                                   'OWN'),
            ('CLIENT_USER',    'pod',       'VIEW',                                         'OWN'),
            ('CLIENT_USER',    'billing',   'VIEW,PRINT',                                   'OWN'),
            ('CLIENT_USER',    'quotation', 'VIEW,APPROVE',                                 'OWN'),
            ('CLIENT_USER',    'report',    'VIEW,EXPORT',                                  'OWN')
           ) AS m(role_code, module, actions, scope)
      JOIN role r ON r.tenant_id = v_tenant AND r.code = m.role_code
     CROSS JOIN LATERAL unnest(string_to_array(m.actions, ',')) AS a(action);

    SELECT count(*) INTO v_count FROM role WHERE tenant_id = v_tenant;
    RETURN v_count;
END
$$;

-- ---------------------------------------------------------------------
-- Parties: consignors, consignees, bill-to (clients and walk-ins)
-- ---------------------------------------------------------------------
CREATE SEQUENCE party_code_seq;

CREATE TABLE party (
    id                    uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    code                  text          NOT NULL DEFAULT 'P' || lpad(nextval('party_code_seq')::text, 6, '0'),
    legal_name            text          NOT NULL,
    trade_name            text,
    party_kind            text          NOT NULL DEFAULT 'BUSINESS' CHECK (party_kind IN ('BUSINESS', 'INDIVIDUAL')),
    roles                 text[]        NOT NULL,
    account_type          text          NOT NULL DEFAULT 'CASH' CHECK (account_type IN ('CONTRACT', 'CASH', 'FORWARDER', 'GOVERNMENT')),
    status                text          NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('PROSPECT', 'ACTIVE', 'ON_HOLD', 'BLOCKED', 'INACTIVE')),
    status_reason         text,
    mobile                text,
    email                 citext,
    website               text,
    industry              text,
    priority_tier         char(1)       CHECK (priority_tier IN ('A', 'B', 'C')),
    default_payment_type  text          CHECK (default_payment_type IN ('PAID', 'TO_PAY', 'TBB')),
    billing_cycle         text          CHECK (billing_cycle IN ('PER_GR', 'WEEKLY', 'FORTNIGHTLY', 'MONTHLY')),
    credit_limit          numeric(14, 2) CHECK (credit_limit >= 0),
    credit_days           smallint      CHECK (credit_days BETWEEN 0 AND 365),
    parent_party_id       uuid          REFERENCES party (id),
    home_location_id      uuid          REFERENCES location (id),
    salesperson_user_id   uuid          REFERENCES app_user (id),
    notes                 text,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    created_by            text,
    updated_at            timestamptz   NOT NULL DEFAULT now(),
    deleted_at            timestamptz,
    deleted_by            text,
    delete_reason         text,
    CONSTRAINT party_roles_chk CHECK (
        cardinality(roles) > 0 AND roles <@ ARRAY['CONSIGNOR', 'CONSIGNEE', 'BILL_TO']::text[])
);

CREATE UNIQUE INDEX party_code_uq ON party (tenant_id, upper(code)) WHERE deleted_at IS NULL;
CREATE INDEX party_name_idx   ON party (tenant_id, lower(legal_name)) WHERE deleted_at IS NULL;
CREATE INDEX party_mobile_idx ON party (tenant_id, mobile) WHERE deleted_at IS NULL;
SELECT tms_secure_tenant_table('party');

ALTER TABLE app_user ADD CONSTRAINT app_user_party_fk FOREIGN KEY (party_id) REFERENCES party (id);

CREATE TABLE party_address (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenant (id),
    party_id         uuid        NOT NULL REFERENCES party (id) ON DELETE CASCADE,
    address_type     text        NOT NULL CHECK (address_type IN ('BILLING', 'REGISTERED', 'PICKUP', 'DELIVERY', 'PLANT', 'WAREHOUSE')),
    label            text,
    line1            text        NOT NULL,
    line2            text,
    city_id          uuid        REFERENCES city (id),
    state_code       text,
    postal_code      text,
    country_code     char(2)     REFERENCES country_pack (code),
    latitude         numeric(9, 6),
    longitude        numeric(9, 6),
    contact_name     text,
    contact_phone    text,
    receiving_hours  text,
    driver_notes     text,
    is_default       boolean     NOT NULL DEFAULT false,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX party_address_party_idx ON party_address (party_id);
CREATE UNIQUE INDEX party_address_default_uq ON party_address (party_id, address_type) WHERE is_default;
SELECT tms_secure_tenant_table('party_address');
