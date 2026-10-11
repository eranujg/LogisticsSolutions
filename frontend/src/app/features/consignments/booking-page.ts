import { DecimalPipe } from '@angular/common';
import { Component, computed, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { Subject, catchError, debounceTime, forkJoin, of, switchMap } from 'rxjs';
import { ApiService, errorMessage } from '../../core/api/api.service';
import { BookingOffice, ChargeHead, Consignment, ConsignmentPricing } from '../../core/api/models';
import { CityPicker } from '../../core/ui/city-picker';
import {
  CN_PAYMENT_TYPES, MOVEMENT_TYPES, PACKAGE_TYPES, PICKUP_TYPES, SERVICES, TAX_PAID_BY, humanize, options
} from '../../core/ui/labels';
import { NotifyService } from '../../core/ui/notify.service';
import { PartyPicker } from '../../core/ui/party-picker';
import { SessionService } from '../../core/session/session.service';

interface BookingForm {
  manualNo: string;
  manualBookNo: string;
  cnDate: string;
  bookingLocationId: string | null;
  deliveryLocationId: string | null;
  originCityId: string | null;
  originCityName: string | null;
  destinationCityId: string | null;
  destinationCityName: string | null;
  paymentType: string;
  movementType: string;
  service: string;
  pickupType: string;
  deliveryType: string;
  vehicleType: string;
  expectedDeliveryDate: string;
  consignorId: string | null;
  consignorName: string | null;
  consigneeId: string | null;
  consigneeName: string | null;
  billToId: string | null;
  billToName: string | null;
  declaredValue: number | null;
  invoiceNumbers: string;
  ewayBillNo: string;
  ewayBillValidUntil: string;
  risk: string;
  privateMarks: string;
  instructions: string;
  freight: number | null;
  discount: number | null;
  overrideReason: string;
  taxPaidBy: string | null;
  taxRate: number | null;
}

interface PackageRow {
  key: number;
  packages: number | null;
  packageType: string;
  saidToContain: string;
  hsnCode: string;
  actualWeightKg: number | null;
  volumeCft: number | null;
  value: number | null;
}

const today = () => new Date().toISOString().slice(0, 10);
const num = (v: unknown): number | null => (v === null || v === undefined || v === '' ? null : Number(v));

/**
 * GR booking. Prices itself from the rate cards as the clerk types (preview),
 * shows what needs approval, and books with a number from the office series.
 */
@Component({
  selector: 'tms-booking-page',
  imports: [
    FormsModule, DecimalPipe, RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatProgressBarModule, MatSelectModule, MatTooltipModule, CityPicker, PartyPicker
  ],
  templateUrl: './booking-page.html',
  styleUrl: './booking-page.scss'
})
export class BookingPage {
  private readonly api = inject(ApiService);
  private readonly notify = inject(NotifyService);
  private readonly router = inject(Router);
  readonly session = inject(SessionService);

  /** Route parameter when editing. */
  readonly id = input<string>();

  readonly humanize = humanize;
  readonly paymentOptions = options(CN_PAYMENT_TYPES);
  readonly movementOptions = options(MOVEMENT_TYPES);
  readonly serviceOptions = options(SERVICES);
  readonly pickupOptions = options(PICKUP_TYPES);
  readonly taxOptions = options(TAX_PAID_BY);
  readonly packageTypes = PACKAGE_TYPES;

  readonly offices = signal<BookingOffice[]>([]);
  readonly heads = signal<ChargeHead[]>([]);
  readonly editing = signal<Consignment | null>(null);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly pricing = signal<ConsignmentPricing | null>(null);
  readonly pricingError = signal<string | null>(null);
  readonly pricingBusy = signal(false);
  readonly formError = signal<string | null>(null);
  readonly rows = signal<PackageRow[]>([]);

  f: BookingForm = this.emptyForm();
  /** Charge amounts the clerk changed; null means "as on the rate card". */
  private chargeEdits: Map<string, number> | null = null;
  private nextKey = 1;
  private readonly previews = new Subject<void>();

  readonly office = computed(() => this.offices().find((o) => o.id === this.officeId()) ?? null);
  private readonly officeId = signal<string | null>(null);
  readonly noteName = computed(() => (this.office()?.consignmentNoteName ?? 'GR').split('/')[0].trim());
  readonly totals = computed(() => this.rows().reduce((t, r) => ({
    packages: t.packages + (Number(r.packages) || 0),
    weight: t.weight + (Number(r.actualWeightKg) || 0),
    volume: t.volume + (Number(r.volumeCft) || 0),
    value: t.value + (Number(r.value) || 0)
  }), { packages: 0, weight: 0, volume: 0, value: 0 }));
  readonly optionalHeads = computed(() => {
    const used = new Set((this.pricing()?.charges ?? []).map((c) => c.chargeHeadId));
    return this.heads().filter((h) => h.active && h.calcMethod !== 'RATE_CARD' && !used.has(h.id));
  });

  constructor() {
    this.previews.pipe(
      debounceTime(400),
      switchMap(() => {
        const body = this.ready() ? this.body() : null;
        if (!body) {
          this.pricing.set(null);
          this.pricingError.set(null);
          return of(null);
        }
        this.pricingBusy.set(true);
        return this.api.previewConsignment(body).pipe(catchError((error) => {
          this.pricingError.set(errorMessage(error));
          this.pricing.set(null);
          return of(null);
        }));
      }),
      takeUntilDestroyed(inject(DestroyRef))
    ).subscribe((pricing) => {
      this.pricingBusy.set(false);
      if (pricing) {
        this.pricingError.set(null);
        this.pricing.set(pricing);
      }
    });

    effect(() => {
      const id = this.id();
      untracked(() => this.load(id));
    });
  }

  private emptyForm(): BookingForm {
    return {
      manualNo: '', manualBookNo: '', cnDate: today(), bookingLocationId: null, deliveryLocationId: null,
      originCityId: null, originCityName: null, destinationCityId: null, destinationCityName: null,
      paymentType: 'PAID', movementType: 'DIRECT', service: 'PTL', pickupType: 'GODOWN', deliveryType: 'GODOWN',
      vehicleType: '', expectedDeliveryDate: '', consignorId: null, consignorName: null, consigneeId: null,
      consigneeName: null, billToId: null, billToName: null, declaredValue: null, invoiceNumbers: '', ewayBillNo: '',
      ewayBillValidUntil: '', risk: 'OWNER', privateMarks: '', instructions: '', freight: null, discount: null,
      overrideReason: '', taxPaidBy: null, taxRate: null
    };
  }

  private newRow(): PackageRow {
    return {
      key: this.nextKey++, packages: null, packageType: 'CARTON', saidToContain: '', hsnCode: '', actualWeightKg: null,
      volumeCft: null, value: null
    };
  }

  private load(id: string | undefined): void {
    this.loading.set(true);
    forkJoin({
      offices: this.api.bookingOffices(),
      heads: this.session.can('rate_card.view') ? this.api.chargeHeads().pipe(catchError(() => of([]))) : of([]),
      cn: id ? this.api.consignment(id) : of(null)
    }).subscribe({
      next: ({ offices, heads, cn }) => {
        this.offices.set(offices);
        this.heads.set(heads);
        if (cn) {
          this.fromConsignment(cn);
        } else {
          this.f = this.emptyForm();
          this.rows.set([this.newRow()]);
          this.chargeEdits = null;
          const mine = this.session.me()?.locationIds ?? [];
          const office = offices.find((o) => mine.includes(o.id)) ?? (offices.length === 1 ? offices[0] : null);
          if (office) {
            this.pickOffice(office.id);
          }
        }
        this.loading.set(false);
        this.changed();
      },
      error: (error) => {
        this.loading.set(false);
        this.notify.error(`Could not open the booking screen. ${errorMessage(error)}`);
      }
    });
  }

  private fromConsignment(cn: Consignment): void {
    const s = cn.summary;
    this.editing.set(cn);
    this.f = {
      ...this.emptyForm(),
      cnDate: s.cnDate, bookingLocationId: s.bookingLocationId, deliveryLocationId: cn.deliveryLocationId,
      originCityId: cn.originCityId, originCityName: s.originCity, destinationCityId: cn.destinationCityId,
      destinationCityName: s.destinationCity, paymentType: s.paymentType, movementType: cn.movementType,
      service: s.service, pickupType: cn.pickupType, deliveryType: cn.deliveryType, vehicleType: cn.vehicleType ?? '',
      expectedDeliveryDate: cn.expectedDeliveryDate ?? '', consignorId: cn.consignorId, consignorName: s.consignorName,
      consigneeId: cn.consigneeId, consigneeName: s.consigneeName, billToId: cn.billToId, billToName: s.billToName,
      declaredValue: cn.declaredValue, invoiceNumbers: cn.invoiceNumbers.join(', '), ewayBillNo: cn.ewayBillNo ?? '',
      ewayBillValidUntil: cn.ewayBillValidUntil ?? '', risk: cn.risk, privateMarks: cn.privateMarks ?? '',
      instructions: cn.instructions ?? '',
      freight: cn.quotedFreight !== null && cn.freight === cn.quotedFreight ? null : cn.freight,
      discount: cn.discount || null, overrideReason: cn.overrideReason ?? '', taxPaidBy: cn.taxPaidBy,
      taxRate: cn.taxRate || null
    };
    this.officeId.set(s.bookingLocationId);
    this.chargeEdits = new Map(cn.charges.map((c) => [c.chargeHeadId, c.amount]));
    this.rows.set(cn.packages.map((p) => ({
      key: this.nextKey++, packages: p.packages, packageType: p.packageType, saidToContain: p.saidToContain,
      hsnCode: p.hsnCode ?? '', actualWeightKg: p.actualWeightKg, volumeCft: p.volumeCft, value: p.value
    })));
  }

  pickOffice(id: string | null): void {
    this.f.bookingLocationId = id;
    this.officeId.set(id);
    const office = this.offices().find((o) => o.id === id);
    if (office?.cityId && !this.f.originCityId) {
      this.f.originCityId = office.cityId;
      this.f.originCityName = office.cityName;
    }
    this.changed();
  }

  /** Any change that can affect the price: re-run the preview shortly. */
  changed(): void {
    this.previews.next();
  }

  addRow(): void {
    this.rows.update((rows) => [...rows, this.newRow()]);
  }

  removeRow(row: PackageRow): void {
    this.rows.update((rows) => rows.filter((r) => r !== row));
    this.changed();
  }

  rowsChanged(): void {
    this.rows.update((rows) => [...rows]);
    this.changed();
  }

  setCharge(headId: string, amount: unknown): void {
    if (!this.chargeEdits) {
      this.chargeEdits = new Map((this.pricing()?.charges ?? []).map((c) => [c.chargeHeadId, c.amount]));
    }
    this.chargeEdits.set(headId, Number(amount) || 0);
    this.changed();
  }

  addCharge(head: ChargeHead | null): void {
    if (!head) {
      return;
    }
    this.setCharge(head.id, head.calcMethod === 'FIXED' ? head.defaultValue : 0);
  }

  chargesChanged(): boolean {
    return this.chargeEdits !== null || this.f.freight !== null;
  }

  resetCharges(): void {
    this.chargeEdits = null;
    this.f.freight = null;
    this.changed();
  }

  /** Enough is filled in to price the booking. */
  private ready(): boolean {
    const f = this.f;
    return !!(f.bookingLocationId && f.originCityId && f.destinationCityId && f.consignorId && f.consigneeId
      && this.rows().length && this.rows().every((r) => Number(r.packages) > 0 && r.packageType && r.saidToContain.trim()));
  }

  missing(): string[] {
    const f = this.f;
    const out: string[] = [];
    if (!f.bookingLocationId) out.push('booking office');
    if (!f.originCityId || !f.destinationCityId) out.push('from and to cities');
    if (!f.consignorId) out.push('consignor');
    if (!f.consigneeId) out.push('consignee');
    if (!this.rows().length || !this.rows().every((r) => Number(r.packages) > 0 && r.saidToContain.trim())) {
      out.push('packages and what they contain');
    }
    return out;
  }

  private body(): unknown {
    const f = this.f;
    return {
      manualNo: this.editing() ? null : f.manualNo.trim() || null,
      manualBookNo: f.manualBookNo.trim() || null,
      cnDate: f.cnDate || null,
      bookingLocationId: f.bookingLocationId,
      deliveryLocationId: f.deliveryLocationId,
      originCityId: f.originCityId,
      destinationCityId: f.destinationCityId,
      paymentType: f.paymentType,
      movementType: f.movementType,
      service: f.service,
      pickupType: f.pickupType,
      deliveryType: f.deliveryType,
      vehicleType: f.vehicleType.trim() || null,
      expectedDeliveryDate: f.expectedDeliveryDate || null,
      consignorId: f.consignorId,
      consigneeId: f.consigneeId,
      billToId: f.billToId,
      packages: this.rows().map((r) => ({
        packages: Number(r.packages), packageType: r.packageType, saidToContain: r.saidToContain.trim(),
        hsnCode: r.hsnCode.trim() || null, actualWeightKg: num(r.actualWeightKg), volumeCft: num(r.volumeCft),
        value: num(r.value)
      })),
      declaredValue: num(f.declaredValue),
      invoiceNumbers: f.invoiceNumbers.split(/[,\s]+/).map((s) => s.trim()).filter(Boolean),
      ewayBillNo: f.ewayBillNo.trim() || null,
      ewayBillValidUntil: f.ewayBillValidUntil || null,
      risk: f.risk,
      privateMarks: f.privateMarks.trim() || null,
      instructions: f.instructions.trim() || null,
      freight: num(f.freight),
      charges: this.chargeEdits ? [...this.chargeEdits].map(([chargeHeadId, amount]) => ({ chargeHeadId, amount })) : null,
      discount: num(f.discount),
      overrideReason: f.overrideReason.trim() || null,
      taxPaidBy: f.taxPaidBy,
      taxRate: num(f.taxRate)
    };
  }

  save(next = false): void {
    const missing = this.missing();
    if (missing.length) {
      this.formError.set(`Fill in: ${missing.join(', ')}.`);
      return;
    }
    const p = this.pricing();
    if (p?.needsApproval && !this.f.overrideReason.trim()) {
      this.formError.set('Give a reason for the changes that need approval.');
      return;
    }
    this.saving.set(true);
    this.formError.set(null);
    const editing = this.editing();
    this.api.saveConsignment(editing?.summary.id ?? null, this.body()).subscribe({
      next: (cn) => {
        this.saving.set(false);
        this.notify.success(`${this.noteName()} ${cn.summary.cnNo} ${editing ? 'saved' : 'booked'} · total ${cn.summary.total}`);
        if (next && !editing) {
          this.nextBooking();
        } else {
          void this.router.navigate(['/consignments', cn.summary.id]);
        }
      },
      error: (error) => {
        this.saving.set(false);
        this.formError.set(errorMessage(error));
      }
    });
  }

  /** Keeps office, date, route and payment; clears parties-specific and goods details. */
  private nextBooking(): void {
    const keep = this.f;
    this.f = {
      ...this.emptyForm(), cnDate: keep.cnDate, bookingLocationId: keep.bookingLocationId, originCityId: keep.originCityId,
      originCityName: keep.originCityName, paymentType: keep.paymentType, service: keep.service,
      consignorId: keep.consignorId, consignorName: keep.consignorName
    };
    this.rows.set([this.newRow()]);
    this.chargeEdits = null;
    this.pricing.set(null);
  }
}
