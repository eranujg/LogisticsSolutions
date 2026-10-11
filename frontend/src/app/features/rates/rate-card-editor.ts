import { Component, computed, effect, inject, input, signal, TemplateRef, untracked, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { Observable, forkJoin, of } from 'rxjs';
import { ApiService, errorMessage } from '../../core/api/api.service';
import { ChargeHead, RateCard, RateLine } from '../../core/api/models';
import { CityPicker } from '../../core/ui/city-picker';
import { DeleteDialog } from '../../core/ui/delete-dialog';
import { PAYMENT_TYPES, RATE_BASES, SERVICES, humanize, options } from '../../core/ui/labels';
import { NotifyService } from '../../core/ui/notify.service';
import { PartyPicker } from '../../core/ui/party-picker';
import { SessionService } from '../../core/session/session.service';
import { statusTag } from './rate-cards-page';

interface CardDraft {
  name: string;
  partyId: string | null;
  partyName: string | null;
  contractRef: string;
  currency: string;
  validFrom: string;
  validTo: string;
  fallbackToStandard: boolean;
  notes: string;
}

interface LineDraft extends RateLine {
  key: number;
  showSlabs: boolean;
}

interface ChargeDraft {
  head: ChargeHead;
  use: boolean;
  value: number;
  minAmount: number;
  isAuto: boolean;
}

const today = () => new Date().toISOString().slice(0, 10);

/**
 * One rate card: details, lanes with slabs, and client charge values. Drafts are
 * edited here; active cards are read-only and changed through a revision.
 */
@Component({
  selector: 'tms-rate-card-editor',
  imports: [
    FormsModule, RouterLink, MatButtonModule, MatCheckboxModule, MatDialogModule, MatFormFieldModule, MatIconModule,
    MatInputModule, MatProgressBarModule, MatSelectModule, MatTooltipModule, CityPicker, PartyPicker
  ],
  templateUrl: './rate-card-editor.html',
  styleUrl: './rate-card-editor.scss'
})
export class RateCardEditor {
  private readonly api = inject(ApiService);
  private readonly notify = inject(NotifyService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  readonly session = inject(SessionService);

  /** Route parameter: a card id, or "new". */
  readonly id = input<string>();

  readonly humanize = humanize;
  readonly statusTag = statusTag;
  readonly serviceOptions = options(SERVICES);
  readonly basisOptions = options(RATE_BASES);
  readonly paymentOptions = options(PAYMENT_TYPES);

  readonly card = signal<RateCard | null>(null);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);
  /** Header fields, edited in place through ngModel. */
  draft: CardDraft = this.emptyDraft();
  readonly lines = signal<LineDraft[]>([]);
  readonly charges = signal<ChargeDraft[]>([]);
  private nextKey = 1;

  readonly isNew = computed(() => !this.id() || this.id() === 'new');
  readonly status = computed(() => this.card()?.card.status ?? 'DRAFT');
  readonly editable = computed(() =>
    this.status() === 'DRAFT' && this.session.can(this.isNew() ? 'rate_card.create' : 'rate_card.edit'));
  readonly title = computed(() => {
    const c = this.card()?.card;
    return c ? `${c.code} v${c.version}` : 'New rate card';
  });

  private readonly reviseTemplate = viewChild.required<TemplateRef<unknown>>('reviseDialog');
  private reviseRef: MatDialogRef<unknown> | null = null;
  readonly reviseFrom = signal('');

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => this.load(id));
    });
  }

  private emptyDraft(): CardDraft {
    return {
      name: '', partyId: null, partyName: null, contractRef: '', currency: 'INR', validFrom: today(), validTo: '',
      fallbackToStandard: true, notes: ''
    };
  }

  private load(id: string | undefined): void {
    this.loading.set(true);
    this.formError.set(null);
    const card$: Observable<RateCard | null> = !id || id === 'new' ? of(null) : this.api.rateCard(id);
    forkJoin({ card: card$, heads: this.api.chargeHeads() }).subscribe({
      next: ({ card, heads }) => {
        this.apply(card, heads);
        this.loading.set(false);
      },
      error: (error) => {
        this.loading.set(false);
        this.notify.error(`Could not load the rate card. ${errorMessage(error)}`);
      }
    });
  }

  private apply(card: RateCard | null, heads: ChargeHead[]): void {
    this.card.set(card);
    if (card) {
      const c = card.card;
      this.draft = ({
        name: c.name, partyId: c.partyId, partyName: c.partyName, contractRef: c.contractRef ?? '',
        currency: c.currency, validFrom: c.validFrom, validTo: c.validTo ?? '',
        fallbackToStandard: c.fallbackToStandard, notes: card.notes ?? ''
      });
      this.lines.set(card.lines.map((line) => ({ ...line, slabs: line.slabs.map((s) => ({ ...s })), key: this.nextKey++, showSlabs: false })));
    } else {
      this.draft = this.emptyDraft();
      this.lines.set([this.newLine()]);
    }
    const own = new Map((card?.charges ?? []).map((c) => [c.chargeHeadId, c]));
    this.charges.set(heads
      .filter((head) => head.calcMethod !== 'RATE_CARD' && head.active)
      .map((head) => {
        const c = own.get(head.id);
        return {
          head, use: !!c, value: c?.value ?? head.defaultValue, minAmount: c?.minAmount ?? head.minAmount,
          isAuto: c?.isAuto ?? true
        };
      }));
  }

  private newLine(): LineDraft {
    return {
      key: this.nextKey++, showSlabs: false, originCityId: null, originCityName: null, destinationCityId: null,
      destinationCityName: null, originLocationId: null, destinationLocationId: null, service: null, vehicleType: null,
      commodity: null, paymentType: null, rateBasis: 'PER_KG', rate: 0, minCharge: 0, minWeightKg: 0,
      volumetricKgPerCft: 0, transitDays: null, slabs: []
    };
  }

  /** Re-emits the lines after an in-place change (slabs added or removed). */
  touch(): void {
    this.lines.update((list) => [...list]);
  }

  addLine(): void {
    this.lines.update((list) => [...list, this.newLine()]);
  }

  copyLine(line: LineDraft): void {
    const copy: LineDraft = { ...line, id: undefined, key: this.nextKey++, slabs: line.slabs.map((s) => ({ ...s, id: undefined })) };
    this.lines.update((list) => {
      const index = list.indexOf(line);
      return [...list.slice(0, index + 1), copy, ...list.slice(index + 1)];
    });
  }

  removeLine(line: LineDraft): void {
    this.lines.update((list) => list.filter((l) => l !== line));
  }

  toggleSlabs(line: LineDraft): void {
    line.showSlabs = !line.showSlabs;
    if (line.showSlabs && !line.slabs.length) {
      line.slabs = [{ fromQty: 0, toQty: null, rate: line.rate }];
    }
    this.touch();
  }

  addSlab(line: LineDraft): void {
    const last = line.slabs.at(-1);
    const from = last?.toQty ?? (last ? last.fromQty + 100 : 0);
    if (last && last.toQty === null) {
      last.toQty = from || last.fromQty + 100;
    }
    line.slabs = [...line.slabs, { fromQty: last?.toQty ?? from, toQty: null, rate: last?.rate ?? line.rate }];
    this.touch();
  }

  removeSlab(line: LineDraft, index: number): void {
    line.slabs = line.slabs.filter((_, i) => i !== index);
    this.touch();
  }

  slabUnit(line: LineDraft): string {
    return line.rateBasis === 'PER_PACKAGE' ? 'packages' : 'kg';
  }

  rateHint(basis: string): string {
    return {
      PER_KG: 'per kg', PER_TONNE: 'per tonne', PER_PACKAGE: 'per package', PER_TRIP: 'per trip', PER_KM: 'per km',
      PERCENT_OF_VALUE: '% of value'
    }[basis] ?? '';
  }

  private validate(): string | null {
    const d = this.draft;
    if (!d.name.trim()) {
      return 'Enter a name for the rate card.';
    }
    if (!d.validFrom) {
      return 'Enter the date the rates start.';
    }
    if (d.validTo && d.validTo < d.validFrom) {
      return 'Valid to must be on or after valid from.';
    }
    for (const [i, line] of this.lines().entries()) {
      if (line.rate === null || line.rate === undefined || Number(line.rate) < 0) {
        return `Lane ${i + 1}: enter a rate of 0 or more.`;
      }
    }
    return null;
  }

  private body(): unknown {
    const d = this.draft;
    return {
      name: d.name.trim(),
      partyId: d.partyId,
      contractRef: d.contractRef || null,
      currency: d.currency || 'INR',
      validFrom: d.validFrom,
      validTo: d.validTo || null,
      fallbackToStandard: d.fallbackToStandard,
      notes: d.notes || null,
      lines: this.lines().map((l) => ({
        originCityId: l.originCityId, destinationCityId: l.destinationCityId, service: l.service,
        vehicleType: l.vehicleType || null, commodity: l.commodity || null, paymentType: l.paymentType,
        rateBasis: l.rateBasis, rate: Number(l.rate), minCharge: Number(l.minCharge) || 0,
        minWeightKg: Number(l.minWeightKg) || 0, volumetricKgPerCft: Number(l.volumetricKgPerCft) || 0,
        transitDays: l.transitDays === null || (l.transitDays as unknown) === '' ? null : Number(l.transitDays),
        slabs: l.slabs.map((s) => ({
          fromQty: Number(s.fromQty) || 0,
          toQty: s.toQty === null || (s.toQty as unknown) === '' ? null : Number(s.toQty),
          rate: Number(s.rate) || 0
        }))
      })),
      charges: this.charges().filter((c) => c.use).map((c) => ({
        chargeHeadId: c.head.id, value: Number(c.value) || 0, minAmount: Number(c.minAmount) || 0, isAuto: c.isAuto
      }))
    };
  }

  save(then?: (card: RateCard) => void): void {
    const problem = this.validate();
    if (problem) {
      this.formError.set(problem);
      return;
    }
    this.saving.set(true);
    this.formError.set(null);
    const id = this.card()?.card.id ?? null;
    this.api.saveRateCard(id, this.body()).subscribe({
      next: (card) => {
        this.saving.set(false);
        if (then) {
          then(card);
          return;
        }
        this.notify.success(`Saved ${card.card.code}`);
        this.afterChange(card, !id);
      },
      error: (error) => {
        this.saving.set(false);
        this.formError.set(errorMessage(error));
      }
    });
  }

  /** Saves the draft, then activates it (needs approval permission). */
  activate(): void {
    const run = (card: RateCard) => {
      this.saving.set(true);
      this.api.setRateCardStatus(card.card.id, 'ACTIVE').subscribe({
        next: (active) => {
          this.saving.set(false);
          this.notify.success(`${active.card.code} v${active.card.version} is active`);
          this.afterChange(active, this.isNew());
        },
        error: (error) => {
          this.saving.set(false);
          this.formError.set(errorMessage(error));
          if (this.isNew()) {
            this.afterChange(card, true);
          } else {
            this.card.set(card);
          }
        }
      });
    };
    if (this.editable()) {
      this.save(run);
    } else if (this.card()) {
      run(this.card()!);
    }
  }

  suspend(): void {
    const card = this.card();
    if (!card) {
      return;
    }
    DeleteDialog.ask(this.dialog, `${card.card.code} v${card.card.version}`, 'Suspend',
      'Bookings stop using these rates until the card is activated again.').subscribe((reason) => {
      if (!reason) {
        return;
      }
      this.api.setRateCardStatus(card.card.id, 'SUSPENDED', reason).subscribe({
        next: (updated) => {
          this.notify.success('Rate card suspended');
          this.afterChange(updated, false);
        },
        error: (error) => this.notify.error(errorMessage(error))
      });
    });
  }

  openRevise(): void {
    const tomorrow = new Date(Date.now() + 86_400_000).toISOString().slice(0, 10);
    this.reviseFrom.set(tomorrow);
    this.reviseRef = this.dialog.open(this.reviseTemplate(), { width: 'min(28rem, 95vw)' });
  }

  revise(): void {
    const card = this.card();
    if (!card || !this.reviseFrom()) {
      return;
    }
    this.api.reviseRateCard(card.card.id, this.reviseFrom()).subscribe({
      next: (revision) => {
        this.reviseRef?.close();
        this.notify.success(`Draft v${revision.card.version} created. Change the rates, then activate it.`);
        void this.router.navigate(['/rate-cards', revision.card.id]);
      },
      error: (error) => this.notify.error(errorMessage(error))
    });
  }

  closeRevise(): void {
    this.reviseRef?.close();
  }

  remove(): void {
    const card = this.card();
    if (!card) {
      return;
    }
    DeleteDialog.ask(this.dialog, `${card.card.code} v${card.card.version}`).subscribe((reason) => {
      if (!reason) {
        return;
      }
      this.api.softDelete('rate-cards', card.card.id, reason).subscribe({
        next: () => {
          this.notify.success('Rate card moved to the recycle bin');
          void this.router.navigate(['/rate-cards']);
        },
        error: (error) => this.notify.error(errorMessage(error))
      });
    });
  }

  private afterChange(card: RateCard, created: boolean): void {
    if (created) {
      void this.router.navigate(['/rate-cards', card.card.id], { replaceUrl: true });
    } else {
      this.api.chargeHeads().subscribe((heads) => this.apply(card, heads));
    }
  }
}
