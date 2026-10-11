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
