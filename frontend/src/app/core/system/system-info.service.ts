import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface SystemInfo {
  app: string;
  databaseTime: string;
  tenantCount: number;
}

@Injectable({ providedIn: 'root' })
export class SystemInfoService {
  private readonly http = inject(HttpClient);

  getInfo(): Observable<SystemInfo> {
    return this.http.get<SystemInfo>('/api/v1/system/info');
  }
}
