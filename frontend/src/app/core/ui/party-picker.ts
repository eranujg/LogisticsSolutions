import { Component, DestroyRef, inject, input, linkedSignal, model, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Subject, debounceTime, switchMap, of, catchError } from 'rxjs';
import { ApiService } from '../api/api.service';
import { Party } from '../api/models';

/** Client / party search box; binds the chosen party's id and name. Empty text means no party. */
@Component({
  selector: 'tms-party-picker',
  imports: [FormsModule, MatFormFieldModule, MatInputModule, MatAutocompleteModule],
  template: `
    <mat-form-field class="picker-field">
      <mat-label>{{ label() }}</mat-label>
      <input matInput [matAutocomplete]="auto" [ngModel]="text()" (ngModelChange)="onType($event)"
             [placeholder]="placeholder()" [disabled]="disabled()" autocomplete="off" />
      <mat-autocomplete #auto [displayWith]="display" (optionSelected)="pick($event.option.value)">
        @for (party of suggestions(); track party.id) {
          <mat-option [value]="party">{{ party.legalName }} <span class="muted">{{ party.code }}</span></mat-option>
        }
      </mat-autocomplete>
      @if (hint()) { <mat-hint>{{ hint() }}</mat-hint> }
    </mat-form-field>
  `,
  styles: [':host { display: block; } .picker-field { width: 100%; }']
})
export class PartyPicker {
  private readonly api = inject(ApiService);

  readonly label = input('Client');
  readonly placeholder = input('Search name, code or mobile');
  readonly hint = input('');
  readonly disabled = input(false);
  readonly partyId = model<string | null>(null);
  readonly partyName = model<string | null>(null);

  readonly text = linkedSignal(() => this.partyName() ?? '');
  readonly suggestions = signal<Party[]>([]);
  private readonly queries = new Subject<string>();

  constructor() {
    this.queries.pipe(
      debounceTime(250),
      switchMap((q) => (q.trim().length < 2 ? of([]) : this.api.parties({ q, limit: 15 }).pipe(catchError(() => of([]))))),
      takeUntilDestroyed(inject(DestroyRef))
    ).subscribe((parties) => this.suggestions.set(parties));
  }

  display = (value: Party | string | null): string => (typeof value === 'string' ? value : value?.legalName ?? '');

  onType(value: Party | string): void {
    if (typeof value !== 'string') {
      return;
    }
    this.text.set(value);
    if (this.partyId()) {
      this.partyId.set(null);
      this.partyName.set(null);
    }
    this.queries.next(value);
  }

  pick(party: Party): void {
    this.partyId.set(party.id);
    this.partyName.set(party.legalName);
    this.text.set(party.legalName);
  }
}
