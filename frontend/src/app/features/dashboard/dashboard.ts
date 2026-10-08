import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin, of, catchError, map } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { SessionService } from '../../core/session/session.service';

interface SetupStep {
  label: string;
  detail: string;
  path: string;
  permission: string;
  done: boolean;
}

/** First screen after sign-in: what is set up so far and what to do next. */
@Component({
  selector: 'tms-dashboard',
  imports: [RouterLink],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss'
})
export class Dashboard implements OnInit {
  private readonly api = inject(ApiService);
  readonly session = inject(SessionService);

  readonly counts = signal<{ companies: number; locations: number; parties: number; users: number } | null>(null);

  readonly steps = computed<SetupStep[]>(() => {
    const c = this.counts();
    if (!c) {
      return [];
    }
    return [
      { label: 'Company details', detail: 'Legal name, country, GSTIN or other tax IDs', path: '/company', permission: 'company.view', done: c.companies > 0 },
      { label: 'Offices and locations', detail: 'Head office, booking and delivery offices, godowns', path: '/locations', permission: 'location.view', done: c.locations > 0 },
      { label: 'Clients and parties', detail: 'Consignors, consignees and bill-to parties', path: '/parties', permission: 'party.view', done: c.parties > 0 },
      { label: 'Staff users', detail: 'Clerks, managers and accountants with their roles', path: '/users', permission: 'user.view', done: c.users > 1 }
    ].filter((step) => this.session.can(step.permission));
  });

  readonly remaining = computed(() => this.steps().filter((step) => !step.done).length);

  ngOnInit(): void {
    const count = <T>(permission: string, source: () => import('rxjs').Observable<T[]>) =>
      this.session.can(permission) ? source().pipe(map((rows) => rows.length), catchError(() => of(0))) : of(0);

    forkJoin({
      companies: count('company.view', () => this.api.companies()),
      locations: count('location.view', () => this.api.locations()),
      parties: count('party.view', () => this.api.parties({ limit: 200 })),
      users: count('user.view', () => this.api.users())
    }).subscribe((counts) => this.counts.set(counts));
  }
}
