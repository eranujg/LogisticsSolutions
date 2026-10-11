import { Component, DestroyRef, inject, input, linkedSignal, model, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Subject, debounceTime, switchMap, of, catchError } from 'rxjs';
import { ApiService } from '../api/api.service';
import { City } from '../api/models';

/**
 * City search box. Binds the chosen city's id and name; clearing the text
 * clears the city (useful for "any city" in rate lines).
 */
@Component({
  selector: 'tms-city-picker',
  imports: [FormsModule, MatFormFieldModule, MatInputModule, MatAutocompleteModule],
  template: `
    <mat-form-field [subscriptSizing]="compact() ? 'dynamic' : 'fixed'" class="picker-field">
      @if (label()) { <mat-label>{{ label() }}</mat-label> }
      <input matInput [matAutocomplete]="auto" [ngModel]="text()" (ngModelChange)="onType($event)"
             [placeholder]="placeholder()" [disabled]="disabled()" [attr.aria-label]="label() || placeholder()" autocomplete="off" />
      <mat-autocomplete #auto [displayWith]="display" (optionSelected)="pick($event.option.value)">
        @for (city of suggestions(); track city.id) {
          <mat-option [value]="city">{{ city.name }}<span class="muted">, {{ city.stateCode }}</span></mat-option>
        }
      </mat-autocomplete>
    </mat-form-field>
  `,
  styles: [':host { display: block; } .picker-field { width: 100%; }']
})
export class CityPicker {
  private readonly api = inject(ApiService);

  readonly label = input('');
  readonly placeholder = input('Any city');
  readonly compact = input(false);
  readonly disabled = input(false);
  readonly cityId = model<string | null>(null);
  readonly cityName = model<string | null>(null);

  readonly text = linkedSignal(() => this.cityName() ?? '');
  readonly suggestions = signal<City[]>([]);
  private readonly queries = new Subject<string>();

  constructor() {
    this.queries.pipe(
      debounceTime(200),
      switchMap((q) => (q.trim().length < 2 ? of([]) : this.api.cities({ q, limit: 15 }).pipe(catchError(() => of([]))))),
      takeUntilDestroyed(inject(DestroyRef))
    ).subscribe((cities) => this.suggestions.set(cities));
  }

  display = (value: City | string | null): string => (typeof value === 'string' ? value : value?.name ?? '');

  onType(value: City | string): void {
    if (typeof value !== 'string') {
      return;
    }
    this.text.set(value);
    if (this.cityId()) {
      this.cityId.set(null);
      this.cityName.set(null);
    }
    this.queries.next(value);
  }

  pick(city: City): void {
    this.cityId.set(city.id);
    this.cityName.set(city.name);
    this.text.set(city.name);
  }
}
