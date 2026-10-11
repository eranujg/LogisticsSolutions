import { Component, computed, inject, OnInit, signal, TemplateRef, viewChild } from '@angular/core';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatAutocompleteModule, MatAutocompleteSelectedEvent } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { debounceTime, forkJoin } from 'rxjs';
import { ApiService, errorMessage } from '../../core/api/api.service';
import { City, Company, Location } from '../../core/api/models';
import { SessionService } from '../../core/session/session.service';
import { DeleteDialog } from '../../core/ui/delete-dialog';
import { LOCATION_TYPES, humanize, options } from '../../core/ui/labels';
import { NotifyService } from '../../core/ui/notify.service';

/** Offices and facilities: one location can be several things (head office + booking office + parking). */
@Component({
  selector: 'tms-locations-page',
  imports: [
    ReactiveFormsModule, MatTableModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatIconModule, MatProgressBarModule, MatTooltipModule, MatAutocompleteModule
  ],
  templateUrl: './locations-page.html'
})
export class LocationsPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly fb = inject(FormBuilder);
  private readonly notify = inject(NotifyService);
  private readonly dialog = inject(MatDialog);
  readonly session = inject(SessionService);

  readonly humanize = humanize;
  readonly typeOptions = options(LOCATION_TYPES);
  readonly statusOptions = options(['ACTIVE', 'TEMPORARILY_CLOSED', 'CLOSED']);
  readonly tenureOptions = options(['OWNED', 'RENTED', 'LEASED', 'FRANCHISE', 'SHARED']);

  readonly locations = signal<Location[]>([]);
  readonly companies = signal<Company[]>([]);
  readonly cities = signal<City[]>([]);
  readonly loading = signal(true);

  readonly parentOptions = computed(() =>
    this.locations().filter((l) => l.id !== this.editing()?.id).map((l) => ({ label: `${l.code} ${l.name}`, value: l.id })));
  readonly cityName = computed(() => {
    const map = new Map(this.cities().map((c) => [c.id, c.name]));
    return (id: string | null) => (id ? map.get(id) ?? '' : '');
  });

  readonly columns = ['code', 'name', 'types', 'city', 'manager', 'actions'];
  private readonly editor = viewChild.required<TemplateRef<unknown>>('editor');
  private editorRef: MatDialogRef<unknown> | null = null;
  /** Text typed in the city box; the chosen city id is kept in the form. */
  readonly cityQuery = new FormControl<string | City>('', { nonNullable: true });
  readonly citySuggestions = signal<City[]>([]);

  readonly editing = signal<Location | null>(null);
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  readonly form = this.fb.nonNullable.group({
    companyId: ['', Validators.required],
    code: ['', [Validators.required, Validators.maxLength(20), Validators.pattern(/^[A-Za-z0-9_-]+$/)]],
    name: ['', [Validators.required, Validators.maxLength(200)]],
    types: [['BRANCH_OFFICE'] as string[], Validators.required],
    parentId: [null as string | null],
    status: ['ACTIVE'],
    tenure: [null as string | null],
    addressLine1: [''],
    cityId: [null as string | null],
    postalCode: [''],
    managerName: [''],
    phone: [''],
    email: ['', Validators.email]
  });

  ngOnInit(): void {
    this.reload();
    this.cityQuery.valueChanges.pipe(debounceTime(200)).subscribe((value) => {
      if (typeof value === 'string') {
        this.form.controls.cityId.setValue(null);
        this.api.cities({ q: value, limit: 20 }).subscribe((cities) => this.citySuggestions.set(cities));
      }
    });
  }

  cityLabel = (city: City | string | null): string => (typeof city === 'string' ? city : city ? `${city.name}, ${city.stateCode}` : '');

  pickCity(event: MatAutocompleteSelectedEvent): void {
    const city = event.option.value as City;
    this.form.controls.cityId.setValue(city.id);
    if (!this.cities().some((c) => c.id === city.id)) {
      this.cities.update((list) => [...list, city]);
    }
  }

  reload(): void {
    this.loading.set(true);
    forkJoin({
      locations: this.api.locations(),
      companies: this.api.companies(),
      cities: this.api.cities({ limit: 200 })
    }).subscribe({
      next: ({ locations, companies, cities }) => {
        this.locations.set(locations);
        this.companies.set(companies);
        this.cities.set(cities);
        this.loading.set(false);
      },
      error: (error) => {
        this.loading.set(false);
        this.notify.error(`Could not load locations. ${errorMessage(error)}`);
      }
    });
  }

  openNew(): void {
    this.editing.set(null);
    this.form.reset({ companyId: this.companies()[0]?.id ?? '' });
    this.cityQuery.setValue('', { emitEvent: false });
    this.formError.set(null);
    this.openEditor();
  }

  openEdit(location: Location): void {
    this.editing.set(location);
    this.formError.set(null);
    this.form.reset({
      companyId: location.companyId, code: location.code, name: location.name, types: location.types,
      parentId: location.parentId, status: location.status, tenure: location.tenure,
      addressLine1: location.addressLine1 ?? '', cityId: location.cityId, postalCode: location.postalCode ?? '',
      managerName: location.managerName ?? '', phone: location.phone ?? '', email: location.email ?? ''
    });
    const city = this.cities().find((c) => c.id === location.cityId);
    this.cityQuery.setValue(city ?? '', { emitEvent: false });
    this.openEditor();
  }

  private openEditor(): void {
    this.editorRef = this.dialog.open(this.editor(), { width: 'min(54rem, 95vw)', maxWidth: '95vw', autoFocus: 'first-tabbable' });
  }

  closeEditor(): void {
    this.editorRef?.close();
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const city = this.cities().find((c) => c.id === v.cityId);
    const body = {
      ...v,
      stateCode: city?.stateCode ?? null,
      addressLine1: v.addressLine1 || null,
      postalCode: v.postalCode || null,
      managerName: v.managerName || null,
      phone: v.phone || null,
      email: v.email || null
    };
    const existing = this.editing();
    this.saving.set(true);
    this.formError.set(null);
    this.api.saveLocation(existing?.id ?? null, body).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.closeEditor();
        this.notify.success(`${existing ? 'Updated' : 'Added'} ${saved.code} ${saved.name}`);
        this.reload();
      },
      error: (error) => {
        this.saving.set(false);
        this.formError.set(errorMessage(error));
      }
    });
  }

  askDelete(location: Location): void {
    DeleteDialog.ask(this.dialog, location.name).subscribe((reason) => {
      if (!reason) {
        return;
      }
      this.api.softDelete('locations', location.id, reason).subscribe({
        next: () => {
          this.notify.success(`${location.name} moved to the recycle bin`);
          this.reload();
        },
        error: (error) => this.notify.error(`Could not delete. ${errorMessage(error)}`)
      });
    });
  }

  invalid(name: string): boolean {
    const control = this.form.get(name);
    return !!control && control.invalid && control.touched;
  }
}
