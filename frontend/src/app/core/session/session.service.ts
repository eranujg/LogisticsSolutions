import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { appSettings } from '../config';

export interface Me {
  tenantId: string;
  tenantCode: string | null;
  userId: string | null;
  fullName: string;
  email: string | null;
  permissions: string[];
  locationIds: string[];
  developmentMode: boolean;
}

/** The signed-in user and what they may do. Menus and buttons check `can(...)`. */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly http = inject(HttpClient);

  readonly me = signal<Me | null>(null);
  readonly loadError = signal<string | null>(null);

  private readonly permissions = computed(() => new Set(this.me()?.permissions ?? []));

  async load(): Promise<Me | null> {
    try {
      const me = await firstValueFrom(this.http.get<Me>(`${appSettings.apiBase}/me`));
      this.me.set(me);
      this.loadError.set(null);
      return me;
    } catch (error: unknown) {
      const detail = (error as { error?: { detail?: string } })?.error?.detail;
      this.loadError.set(detail ?? 'Could not load your account.');
      return null;
    }
  }

  /** True when the user has the permission, e.g. `can('party.create')`. */
  can(permission: string): boolean {
    const permissions = this.permissions();
    return permissions.has('*') || permissions.has(permission);
  }
}
