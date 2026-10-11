import { DecimalPipe, LowerCasePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';
import { ApiService, errorMessage } from '../../core/api/api.service';
import { RateQuote } from '../../core/api/models';
import { CityPicker } from '../../core/ui/city-picker';
import { PAYMENT_TYPES, SERVICES, humanize, options } from '../../core/ui/labels';
import { PartyPicker } from '../../core/ui/party-picker';

interface RateInput {
  partyId: string | null;
  partyName: string | null;
  originCityId: string | null;
  originCityName: string | null;
  destinationCityId: string | null;
  destinationCityName: string | null;
  service: string | null;
  paymentType: string | null;
  vehicleType: string;
  actualWeightKg: number | null;
  volumeCft: number | null;
  packages: number | null;
  declaredValue: number | null;
  distanceKm: number | null;
  date: string;
}

/** Prices a shipment from the rate cards, exactly as a booking will. */
@Component({
  selector: 'tms-rate-calculator',
  imports: [
    FormsModule, DecimalPipe, LowerCasePipe, RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatSelectModule, CityPicker, PartyPicker
  ],
  templateUrl: './rate-calculator.html',
  styles: [`
    :host { display: block; min-width: 0; }
    .layout { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(0, 0.9fr); gap: 1.25rem; align-items: start; }
    @media (max-width: 900px) { .layout { grid-template-columns: minmax(0, 1fr); } }
    .panel + .panel { margin-top: 0; }
    .result-head { display: flex; justify-content: space-between; align-items: baseline; gap: 1rem; flex-wrap: wrap; }
    .total { font-size: 1.6rem; font-weight: 700; color: var(--tms-ink-blue); font-variant-numeric: tabular-nums; }
    dl { display: grid; grid-template-columns: auto 1fr; row-gap: 0.35rem; margin: 0.75rem 0; }
    dt { color: var(--tms-muted); padding-right: 1rem; }
    dd { margin: 0; text-align: right; font-variant-numeric: tabular-nums; }
    dd.strong, dt.strong { font-weight: 600; color: var(--tms-ink); border-top: 1px solid var(--tms-rule); padding-top: 0.35rem; }
    .actions { display: flex; justify-content: flex-end; gap: 0.6rem; }
  `]
})
export class RateCalculator {
  private readonly api = inject(ApiService);

  readonly humanize = humanize;
  readonly serviceOptions = options(SERVICES);
  readonly paymentOptions = options(PAYMENT_TYPES);

  input: RateInput = this.empty();
  readonly quote = signal<RateQuote | null>(null);
  readonly error = signal<string | null>(null);
  readonly working = signal(false);

  private empty(): RateInput {
    return {
      partyId: null, partyName: null, originCityId: null, originCityName: null, destinationCityId: null,
      destinationCityName: null, service: null, paymentType: null, vehicleType: '', actualWeightKg: null, volumeCft: null,
      packages: null, declaredValue: null, distanceKm: null, date: new Date().toISOString().slice(0, 10)
    };
  }

  calculate(): void {
    const i = this.input;
    if (!i.originCityId || !i.destinationCityId) {
      this.error.set('Choose the from and to cities.');
      return;
    }
    this.error.set(null);
    this.working.set(true);
    this.api.rateLookup({
      partyId: i.partyId, originCityId: i.originCityId, destinationCityId: i.destinationCityId, service: i.service,
      paymentType: i.paymentType, vehicleType: i.vehicleType || null, actualWeightKg: i.actualWeightKg,
      volumeCft: i.volumeCft, packages: i.packages, declaredValue: i.declaredValue, distanceKm: i.distanceKm, date: i.date
    }).subscribe({
      next: (quote) => {
        this.working.set(false);
        this.quote.set(quote);
      },
      error: (error) => {
        this.working.set(false);
        this.quote.set(null);
        this.error.set(errorMessage(error));
      }
    });
  }

  reset(): void {
    this.input = this.empty();
    this.quote.set(null);
    this.error.set(null);
  }
}
