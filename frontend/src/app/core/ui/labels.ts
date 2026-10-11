/** Human labels for codes used across screens. */

export function humanize(code: string | null | undefined): string {
  if (!code) {
    return '';
  }
  const special: Record<string, string> = {
    TBB: 'To be billed', TO_PAY: 'To pay', OPC: 'OPC', LLP: 'LLP', LLC: 'LLC', HUF: 'HUF', LP: 'LP',
    PTY_LTD: 'Pty Ltd', S_CORP: 'S corporation', C_CORP: 'C corporation', '3PL': '3PL', ODA: 'ODA',
    GST_HST: 'GST/HST', GSTIN: 'GSTIN', PAN: 'PAN', TAN: 'TAN', BN: 'BN', QST: 'QST', EIN: 'EIN', ABN: 'ABN',
    ACN: 'ACN', TFN: 'TFN', USDOT: 'USDOT', MC: 'MC', UCR: 'UCR', IFTA: 'IFTA', CVOR: 'CVOR', NSC: 'NSC',
    CIN: 'CIN', GR: 'GR', POD: 'POD', FTL: 'FTL (full load)', PTL: 'PTL (part load)', ODC: 'ODC (over-dimensional)',
    PER_KG: 'Per kg', PER_KM: 'Per km', PERCENT_OF_VALUE: '% of goods value', PERCENT_OF_FREIGHT: '% of freight',
    RATE_CARD: 'From rate card', CLIENT_CARD: 'Client rate card', STANDARD: 'Standard rates', UDYAM: 'Udyam', NHVAS: 'NHVAS', CBSA_CARRIER_CODE: 'CBSA carrier code'
  };
  if (special[code]) {
    return special[code];
  }
  const text = code.toLowerCase().replace(/_/g, ' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

export interface Option {
  label: string;
  value: string;
}

export function options(codes: readonly string[]): Option[] {
  return codes.map((value) => ({ value, label: humanize(value) }));
}

export const BUSINESS_TYPES = ['TRANSPORTER', 'FLEET_OWNER', 'BROKER', '3PL', 'SHIPPER_OWN_FLEET', 'COURIER'] as const;

export const LOCATION_TYPES = [
  'HEAD_OFFICE', 'REGISTERED_OFFICE', 'REGIONAL_OFFICE', 'BRANCH_OFFICE', 'BOOKING_OFFICE', 'DELIVERY_OFFICE',
  'TRANSIT_HUB', 'DISPATCH_CENTER', 'FRANCHISE_AGENCY', 'COLLECTION_POINT', 'ACCOUNTS_OFFICE', 'SALES_OFFICE',
  'IN_PLANT_OFFICE', 'BORDER_OFFICE', 'WAREHOUSE', 'COLD_STORAGE', 'CONTAINER_YARD', 'TRUCK_CARE_CENTER',
  'REPAIR_HUB', 'SPARE_PARTS_STORE', 'TYRE_SHOP', 'FUEL_STATION', 'EV_CHARGING_DEPOT', 'WEIGHBRIDGE',
  'PARKING_YARD', 'SCRAP_YARD', 'LABOUR_RESIDENCE', 'LABOUR_KITCHEN', 'STAFF_QUARTERS', 'DRIVER_REST_HOUSE',
  'TRAINING_CENTER'
] as const;

export const PARTY_ROLES = ['CONSIGNOR', 'CONSIGNEE', 'BILL_TO'] as const;
export const ACCOUNT_TYPES = ['CASH', 'CONTRACT', 'FORWARDER', 'GOVERNMENT'] as const;
export const PAYMENT_TYPES = ['PAID', 'TO_PAY', 'TBB'] as const;
export const BILLING_CYCLES = ['PER_GR', 'WEEKLY', 'FORTNIGHTLY', 'MONTHLY'] as const;
export const USER_TYPES = [
  'STAFF', 'DRIVER', 'CLIENT', 'CONSIGNEE', 'BOOKING_AGENT', 'DELIVERY_AGENT', 'CROSSING_AGENT',
  'VEHICLE_OWNER', 'BROKER', 'VENDOR', 'AUDITOR', 'LABOUR_CONTRACTOR'
] as const;
export const USER_STATUSES = ['INVITED', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED'] as const;
export const CITY_CLASSES = ['METRO', 'TIER_1', 'TIER_2', 'TIER_3', 'RURAL'] as const;
export const SERVICES = ['FTL', 'PTL', 'EXPRESS', 'LOCAL', 'CONTAINER', 'ODC'] as const;
export const RATE_BASES = ['PER_KG', 'PER_TONNE', 'PER_PACKAGE', 'PER_TRIP', 'PER_KM', 'PERCENT_OF_VALUE'] as const;
export const CHARGE_METHODS = ['FIXED', 'PER_PACKAGE', 'PER_KG', 'PERCENT_OF_FREIGHT', 'PERCENT_OF_VALUE'] as const;
export const EDIT_CONTROLS = ['FREE', 'INCREASE_ONLY', 'LOCKED'] as const;
export const RATE_CARD_STATUSES = ['DRAFT', 'ACTIVE', 'SUSPENDED', 'SUPERSEDED'] as const;
