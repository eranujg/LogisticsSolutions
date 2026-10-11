import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { Router, RouterLink } from '@angular/router';
import { Subject, debounceTime, switchMap } from 'rxjs';
import { ApiService, errorMessage } from '../../core/api/api.service';
import { RateCardSummary } from '../../core/api/models';
import { RATE_CARD_STATUSES, humanize, options } from '../../core/ui/labels';
import { NotifyService } from '../../core/ui/notify.service';
import { SessionService } from '../../core/session/session.service';

export function statusTag(status: string): string {
  return { ACTIVE: 'ok', DRAFT: 'info', SUSPENDED: 'warn', SUPERSEDED: '' }[status] ?? '';
}

/** Client rate cards and standard rates. */
@Component({
  selector: 'tms-rate-cards-page',
  imports: [
    FormsModule, RouterLink, MatTableModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatIconModule, MatProgressBarModule
  ],
  templateUrl: './rate-cards-page.html'
})
export class RateCardsPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly notify = inject(NotifyService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  readonly session = inject(SessionService);

  readonly humanize = humanize;
  readonly statusTag = statusTag;
  readonly statusOptions = options(RATE_CARD_STATUSES);

  readonly cards = signal<RateCardSummary[]>([]);
  readonly loading = signal(true);
  readonly search = signal('');
  readonly status = signal<string | null>(null);
  private readonly searches = new Subject<void>();
  readonly columns = ['code', 'name', 'client', 'validity', 'lines', 'status'];

  ngOnInit(): void {
    this.searches.pipe(
      debounceTime(250),
      switchMap(() => {
        this.loading.set(true);
        return this.api.rateCards({ q: this.search(), status: this.status() });
      }),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: (rows) => {
        this.cards.set(rows);
        this.loading.set(false);
      },
      error: (error) => {
        this.loading.set(false);
        this.notify.error(`Could not load rate cards. ${errorMessage(error)}`);
      }
    });
    this.searches.next();
  }

  onSearch(value: string): void {
    this.search.set(value);
    this.searches.next();
  }

  onStatus(value: string | null): void {
    this.status.set(value);
    this.searches.next();
  }

  open(card: RateCardSummary): void {
    void this.router.navigate(['/rate-cards', card.id]);
  }
}
