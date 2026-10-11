import { DecimalPipe } from '@angular/common';
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
import { ConsignmentSummary } from '../../core/api/models';
import { CN_PAYMENT_TYPES, CN_STATUSES, cnStatusTag, humanize, options } from '../../core/ui/labels';
import { NotifyService } from '../../core/ui/notify.service';
import { SessionService } from '../../core/session/session.service';

const isoDate = (d: Date) => d.toISOString().slice(0, 10);

/** Booked consignments (GRs) with search and filters. */
@Component({
  selector: 'tms-consignments-page',
  imports: [
    FormsModule, DecimalPipe, RouterLink, MatTableModule, MatButtonModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatIconModule, MatProgressBarModule
  ],
  templateUrl: './consignments-page.html'
})
export class ConsignmentsPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly notify = inject(NotifyService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  readonly session = inject(SessionService);

  readonly humanize = humanize;
  readonly statusTag = cnStatusTag;
  readonly statusOptions = options(CN_STATUSES);
  readonly paymentOptions = options(CN_PAYMENT_TYPES);

  readonly rows = signal<ConsignmentSummary[]>([]);
  readonly loading = signal(true);
  filters = {
    q: '',
    status: null as string | null,
    paymentType: null as string | null,
    from: isoDate(new Date(Date.now() - 30 * 86_400_000)),
    to: isoDate(new Date())
  };
  private readonly searches = new Subject<void>();
  readonly columns = ['cnNo', 'date', 'route', 'consignor', 'consignee', 'payment', 'packages', 'total', 'status'];

  ngOnInit(): void {
    this.searches.pipe(
      debounceTime(250),
      switchMap(() => {
        this.loading.set(true);
        return this.api.consignments({ ...this.filters, limit: 300 });
      }),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: (rows) => {
        this.rows.set(rows);
        this.loading.set(false);
      },
      error: (error) => {
        this.loading.set(false);
        this.notify.error(`Could not load consignments. ${errorMessage(error)}`);
      }
    });
    this.searches.next();
  }

  refresh(): void {
    this.searches.next();
  }

  open(row: ConsignmentSummary): void {
    void this.router.navigate(['/consignments', row.id]);
  }
}
