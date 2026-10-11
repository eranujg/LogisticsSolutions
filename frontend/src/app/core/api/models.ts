/** Shapes returned by the backend (see the Java records of the same name). */

export interface TaxRegistration {
  id?: string;
  taxType: string;
  number: string;
  stateCode?: string | null;
  validFrom?: string | null;
  validTo?: string | null;
  verified?: boolean;
}

export interface CountryPack {
  code: string;
  name: string;
  currencyCode: string;
  distanceUnit: string;
  weightUnit: string;
  fyStartMonth: number;
  consignmentNoteName: string;
  companyTaxIdTypes: string[];
  partyTaxIdTypes: string[];
  ownershipTypes: string[];
  regionLabel: string;
}

export interface Region {
  code: string;
  name: string;
  kind: string;
  gstStateCode: string | null;
}

export interface Company {
  id: string;
  legalName: string;
  tradeName: string | null;
  countryCode: string;
  stateCode: string | null;
  businessTypes: string[];
  ownershipType: string;
  baseCurrency: string;
  fyStartMonth: number;
  email: string | null;
  phone: string | null;
  website: string | null;
  addressLine1: string | null;
  addressLine2: string | null;
  cityName: string | null;
  postalCode: string | null;
  status: string;
  taxRegistrations: TaxRegistration[];
}

export interface Location {
  id: string;
  companyId: string;
  code: string;
  name: string;
  types: string[];
  parentId: string | null;
  status: string;
  tenure: string | null;
  addressLine1: string | null;
  cityId: string | null;
  stateCode: string | null;
  postalCode: string | null;
  managerName: string | null;
  phone: string | null;
  email: string | null;
}

export interface City {
  id: string;
  global: boolean;
  countryCode: string;
  stateCode: string;
  name: string;
  aliases: string[];
  district: string | null;
  classification: string | null;
  postalCodes: string[];
  status: string;
}

export interface Party {
  id: string;
  code: string;
  legalName: string;
  tradeName: string | null;
  partyKind: string;
  roles: string[];
  accountType: string;
  status: string;
  mobile: string | null;
  email: string | null;
  defaultPaymentType: string | null;
  billingCycle: string | null;
  creditLimit: number | null;
  creditDays: number | null;
  notes: string | null;
  taxRegistrations: TaxRegistration[];
}

export interface DuplicateMatch {
  id: string;
  code: string;
  legalName: string;
  mobile: string | null;
  matchedOn: string;
}

export interface AppUser {
  id: string;
  userType: string;
  fullName: string;
  email: string | null;
  mobile: string | null;
  status: string;
  homeLocationId: string | null;
  preferredLanguage: string;
  roleIds: string[];
  locationIds: string[];
  lastLoginAt: string | null;
}

export interface RolePermission {
  permissionCode: string;
  scope: string;
}

export interface Role {
  id: string;
  code: string;
  name: string;
  description: string | null;
  system: boolean;
  permissions: RolePermission[];
}

export interface Permission {
  code: string;
  module: string;
  action: string;
  sensitive: boolean;
}

export interface SystemInfo {
  app: string;
  databaseTime: string;
  tenantCount: number;
}

export interface ChargeHead {
  id: string;
  code: string;
  name: string;
  calcMethod: string;
  defaultValue: number;
  minAmount: number;
  isAuto: boolean;
  editControl: string;
  taxable: boolean;
  taxCode: string | null;
  sortOrder: number;
  isSystem: boolean;
  active: boolean;
}

export interface RateCardSummary {
  id: string;
  code: string;
  version: number;
  name: string;
  partyId: string | null;
  partyName: string | null;
  contractRef: string | null;
  currency: string;
  validFrom: string;
  validTo: string | null;
  status: string;
  fallbackToStandard: boolean;
  lineCount: number;
  approvedBy: string | null;
  approvedAt: string | null;
  createdAt: string;
}

export interface RateSlab {
  id?: string;
  fromQty: number;
  toQty: number | null;
  rate: number;
}

export interface RateLine {
  id?: string;
  originCityId: string | null;
  originCityName: string | null;
  destinationCityId: string | null;
  destinationCityName: string | null;
  originLocationId: string | null;
  destinationLocationId: string | null;
  service: string | null;
  vehicleType: string | null;
  commodity: string | null;
  paymentType: string | null;
  rateBasis: string;
  rate: number;
  minCharge: number;
  minWeightKg: number;
  volumetricKgPerCft: number;
  transitDays: number | null;
  slabs: RateSlab[];
}

export interface RateCardCharge {
  chargeHeadId: string;
  code: string;
  name: string;
  calcMethod: string;
  value: number;
  minAmount: number;
  isAuto: boolean;
}

export interface RateCard {
  card: RateCardSummary;
  previousCardId: string | null;
  notes: string | null;
  lines: RateLine[];
  charges: RateCardCharge[];
}

export interface QuoteCharge {
  chargeHeadId: string;
  code: string;
  name: string;
  calcMethod: string;
  value: number;
  amount: number;
  editControl: string;
  taxable: boolean;
}

export interface RateQuote {
  found: boolean;
  blocked: boolean;
  message: string | null;
  source: 'CLIENT_CARD' | 'STANDARD' | 'NONE';
  rateCardId: string | null;
  rateCardCode: string | null;
  rateCardName: string | null;
  currency: string | null;
  lineId: string | null;
  rateBasis: string | null;
  rate: number | null;
  actualWeightKg: number | null;
  volumetricWeightKg: number | null;
  chargeableWeightKg: number | null;
  quantity: number | null;
  freight: number | null;
  minimumApplied: boolean;
  charges: QuoteCharge[];
  total: number | null;
  transitDays: number | null;
}

export interface BookingOffice {
  id: string;
  code: string;
  name: string;
  cityId: string | null;
  cityName: string | null;
  companyName: string;
  countryCode: string;
  currency: string;
  consignmentNoteName: string;
}

export interface ConsignmentSummary {
  id: string;
  cnNo: string;
  cnDate: string;
  status: string;
  paymentType: string;
  service: string;
  bookingLocationId: string;
  bookingLocationCode: string;
  originCity: string;
  destinationCity: string;
  consignorName: string;
  consigneeName: string;
  billToName: string;
  totalPackages: number;
  chargeableWeightKg: number;
  total: number;
  currency: string;
  createdAt: string;
}

export interface ConsignmentPackage {
  id?: string;
  packages: number;
  packageType: string;
  saidToContain: string;
  hsnCode: string | null;
  actualWeightKg: number | null;
  volumeCft: number | null;
  value: number | null;
}

export interface ConsignmentCharge {
  chargeHeadId: string;
  code: string;
  name: string;
  quotedAmount: number | null;
  amount: number;
  taxable: boolean;
}

export interface ConsignmentEvent {
  status: string;
  locationId: string | null;
  note: string | null;
  occurredAt: string;
  userId: string | null;
}

export interface Consignment {
  summary: ConsignmentSummary;
  financialYear: string;
  creation: string;
  manualBookNo: string | null;
  deliveryLocationId: string | null;
  deliveryLocationCode: string | null;
  originCityId: string;
  destinationCityId: string;
  movementType: string;
  pickupType: string;
  deliveryType: string;
  vehicleType: string | null;
  expectedDeliveryDate: string | null;
  consignorId: string;
  consignorTaxId: string | null;
  consignorMobile: string | null;
  consigneeId: string;
  consigneeTaxId: string | null;
  consigneeMobile: string | null;
  billToId: string;
  actualWeightKg: number;
  volumeCft: number;
  declaredValue: number;
  invoiceNumbers: string[];
  ewayBillNo: string | null;
  ewayBillValidUntil: string | null;
  risk: string;
  privateMarks: string | null;
  instructions: string | null;
  rateSource: string;
  rateCardId: string | null;
  rateBasis: string | null;
  rate: number | null;
  quotedFreight: number | null;
  freight: number;
  chargesTotal: number;
  discount: number;
  taxableAmount: number;
  taxPaidBy: string;
  taxRate: number;
  taxAmount: number;
  overrideReason: string | null;
  approvedBy: string | null;
  cancelReason: string | null;
  cancelledAt: string | null;
  cancelledBy: string | null;
  packages: ConsignmentPackage[];
  charges: ConsignmentCharge[];
  events: ConsignmentEvent[];
}

export interface PricedCharge {
  chargeHeadId: string;
  code: string;
  name: string;
  quotedAmount: number | null;
  amount: number;
  editControl: string;
  taxable: boolean;
}

export interface ConsignmentPricing {
  rateSource: string;
  rateCardId: string | null;
  rateCardCode: string | null;
  rateCardLineId: string | null;
  rateBasis: string | null;
  rate: number | null;
  rateMessage: string | null;
  totalPackages: number;
  actualWeightKg: number;
  volumeCft: number;
  chargeableWeightKg: number;
  declaredValue: number;
  quotedFreight: number | null;
  freight: number;
  charges: PricedCharge[];
  chargesTotal: number;
  discount: number;
  taxableAmount: number;
  taxPaidBy: string;
  taxRate: number;
  taxAmount: number;
  total: number;
  currency: string;
  transitDays: number | null;
  needsApproval: boolean;
  approvalReasons: string[];
  warnings: string[];
}
