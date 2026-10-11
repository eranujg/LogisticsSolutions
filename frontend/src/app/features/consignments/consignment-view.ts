import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';
import { ApiService, errorMessage } from '../../core/api/api.service';
import { BookingOffice, Consignment } from '../../core/api/models';
import { DeleteDialog } from '../../core/ui/delete-dialog';
import { cnStatusTag, humanize } from '../../core/ui/labels';
import { NotifyService } from '../../core/ui/notify.service';
import { SessionService } from '../../core/session/session.service';

/** One consignment: printable copy, actions and status history. */
@Component({
  selector: 'tms-consignment-view',
  imports: [DecimalPipe, DatePipe, RouterLink, MatButtonModule, MatIconModule, MatMenuModule, MatProgressBarModule],
  templateUrl: './consignment-view.html',
  styleUrl: './consignment-view.scss'
})
export class ConsignmentView {
  private readonly api = inject(ApiService);
  private readonly notify = inject(NotifyService);
  private readonly dialog = inject(MatDialog);
  readonly session = inject(SessionService);

  readonly id = input<string>();
  readonly humanize = humanize;
  readonly statusTag = cnStatusTag;

  readonly cn = signal<Consignment | null>(null);
  readonly office = signal<BookingOffice | null>(null);
  readonly loading = signal(true);
  readonly noteName = computed(() => (this.office()?.consignmentNoteName ?? 'GR').split('/')[0].trim());
  readonly copies = ['Consignor copy', 'Consignee copy', 'Office copy'];
  readonly printCopy = signal(this.copies[0]);

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => id && this.load(id));
    });
  }

  private load(id: string): void {
    this.loading.set(true);
    forkJoin({ cn: this.api.consignment(id), offices: this.api.bookingOffices().pipe(catchError(() => of([]))) }).subscribe({
      next: ({ cn, offices }) => {
        this.cn.set(cn);
        this.office.set(offices.find((o) => o.id === cn.summary.bookingLocationId) ?? null);
        this.loading.set(false);
      },
      error: (error) => {
        this.loading.set(false);
        this.notify.error(`Could not load. ${errorMessage(error)}`);
      }
    });
  }

  print(copy: string): void {
    this.printCopy.set(copy);
    setTimeout(() => window.print(), 50);
  }

  cancel(): void {
    const cn = this.cn();
    if (!cn) {
      return;
    }
    DeleteDialog.ask(this.dialog, cn.summary.cnNo, 'Cancel',
      'The number is kept and marked cancelled. This cannot be undone.').subscribe((reason) => {
      if (!reason) {
        return;
      }
      this.api.cancelConsignment(cn.summary.id, reason).subscribe({
        next: (updated) => {
          this.cn.set(updated);
          this.notify.success(`${updated.summary.cnNo} cancelled`);
        },
        error: (error) => this.notify.error(errorMessage(error))
      });
    });
  }
}
