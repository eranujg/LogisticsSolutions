import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { appSettings } from '../config';
import {
  AppUser, ChargeHead, City, Company, CountryPack, DuplicateMatch, Location, Party, Permission, RateCard,
  RateCardSummary, RateQuote, Region, Role, SystemInfo
} from './models';

type Params = Record<string, string | number | boolean | null | undefined>;

/** Typed access to the backend REST API. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = appSettings.apiBase;

  systemInfo(): Observable<SystemInfo> {
    return this.http.get<SystemInfo>(`${this.base}/system/info`);
  }

  countryPacks(): Observable<CountryPack[]> {
    return this.http.get<CountryPack[]>(`${this.base}/country-packs`);
  }

  regions(countryCode: string): Observable<Region[]> {
    return this.http.get<Region[]>(`${this.base}/country-packs/${countryCode}/regions`);
  }

  companies(): Observable<Company[]> {
    return this.http.get<Company[]>(`${this.base}/companies`);
  }

  saveCompany(id: string | null, body: unknown): Observable<Company> {
    return id
      ? this.http.put<Company>(`${this.base}/companies/${id}`, body)
      : this.http.post<Company>(`${this.base}/companies`, body);
  }

  locations(params: Params = {}): Observable<Location[]> {
    return this.http.get<Location[]>(`${this.base}/locations`, { params: toParams(params) });
  }

  saveLocation(id: string | null, body: unknown): Observable<Location> {
    return id
      ? this.http.put<Location>(`${this.base}/locations/${id}`, body)
      : this.http.post<Location>(`${this.base}/locations`, body);
  }

  cities(params: Params = {}): Observable<City[]> {
    return this.http.get<City[]>(`${this.base}/cities`, { params: toParams(params) });
  }

  createCity(body: unknown): Observable<City> {
    return this.http.post<City>(`${this.base}/cities`, body);
  }

  parties(params: Params = {}): Observable<Party[]> {
    return this.http.get<Party[]>(`${this.base}/parties`, { params: toParams(params) });
  }

  partyDuplicates(params: Params): Observable<DuplicateMatch[]> {
    return this.http.get<DuplicateMatch[]>(`${this.base}/parties/duplicates`, { params: toParams(params) });
  }

  saveParty(id: string | null, body: unknown): Observable<Party> {
    return id
      ? this.http.put<Party>(`${this.base}/parties/${id}`, body)
      : this.http.post<Party>(`${this.base}/parties`, body);
  }

  users(params: Params = {}): Observable<AppUser[]> {
    return this.http.get<AppUser[]>(`${this.base}/users`, { params: toParams(params) });
  }

  saveUser(id: string | null, body: unknown): Observable<AppUser> {
    return id
      ? this.http.put<AppUser>(`${this.base}/users/${id}`, body)
      : this.http.post<AppUser>(`${this.base}/users`, body);
  }

  roles(): Observable<Role[]> {
    return this.http.get<Role[]>(`${this.base}/roles`);
  }

  permissions(): Observable<Permission[]> {
    return this.http.get<Permission[]>(`${this.base}/permissions`);
  }

  copyRole(id: string, code: string, name: string): Observable<Role> {
    return this.http.post<Role>(`${this.base}/roles/${id}/copy`, { code, name });
  }

  saveRole(id: string, body: unknown): Observable<Role> {
    return this.http.put<Role>(`${this.base}/roles/${id}`, body);
  }

  chargeHeads(): Observable<ChargeHead[]> {
    return this.http.get<ChargeHead[]>(`${this.base}/charge-heads`);
  }

  loadDefaultChargeHeads(): Observable<ChargeHead[]> {
    return this.http.post<ChargeHead[]>(`${this.base}/charge-heads/defaults`, {});
  }

  saveChargeHead(id: string | null, body: unknown): Observable<ChargeHead> {
    return id
      ? this.http.put<ChargeHead>(`${this.base}/charge-heads/${id}`, body)
      : this.http.post<ChargeHead>(`${this.base}/charge-heads`, body);
  }

  rateCards(params: Params = {}): Observable<RateCardSummary[]> {
    return this.http.get<RateCardSummary[]>(`${this.base}/rate-cards`, { params: toParams(params) });
  }

  rateCard(id: string): Observable<RateCard> {
    return this.http.get<RateCard>(`${this.base}/rate-cards/${id}`);
  }

  saveRateCard(id: string | null, body: unknown): Observable<RateCard> {
    return id
      ? this.http.put<RateCard>(`${this.base}/rate-cards/${id}`, body)
      : this.http.post<RateCard>(`${this.base}/rate-cards`, body);
  }

  setRateCardStatus(id: string, status: 'ACTIVE' | 'SUSPENDED', reason?: string): Observable<RateCard> {
    return this.http.put<RateCard>(`${this.base}/rate-cards/${id}/status`, { status, reason: reason ?? null });
  }

  reviseRateCard(id: string, validFrom: string): Observable<RateCard> {
    return this.http.post<RateCard>(`${this.base}/rate-cards/${id}/revise`, { validFrom });
  }

  rateLookup(params: Params): Observable<RateQuote> {
    return this.http.get<RateQuote>(`${this.base}/rate-cards/lookup`, { params: toParams(params) });
  }

  softDelete(resource: string, id: string, reason: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${resource}/${id}`, { params: { reason } });
  }
}

function toParams(params: Params): HttpParams {
  let result = new HttpParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined && value !== '') {
      result = result.set(key, String(value));
    }
  }
  return result;
}

/** Readable message from an API error (RFC 9457 problem or validation errors). */
export function errorMessage(error: unknown): string {
  const body = (error as { error?: { detail?: string; errors?: Record<string, string> } })?.error;
  if (body?.errors && Object.keys(body.errors).length) {
    return Object.entries(body.errors).map(([field, message]) => `${field}: ${message}`).join('; ');
  }
  return body?.detail ?? 'Something went wrong. Check your connection and try again.';
}
