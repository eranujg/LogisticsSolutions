import { Component, DestroyRef, inject, OnInit, signal, TemplateRef, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { Subject, debounceTime, switchMap } from 'rxjs';
import { ApiService, errorMessage } from '../../core/api/api.service';
import { City, CountryPack, Region } from '../../core/api/models';
import { SessionService } from '../../core/session/session.service';
import { CITY_CLASSES, humanize, options } from '../../core/ui/labels';
import { NotifyService } from '../../core/ui/notify.service';

/** Shared city list plus towns the company adds itself. Search also matches old names (Gurgaon, Bombay). */
@Component({
  selector: 'tms-cities-page',
  imports: [
    ReactiveFormsModule, MatTableModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatIconModule, MatProgressBarModule
  ],
  templateUrl: './cities-page.html'
})
export class CitiesPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly fb = inject(FormBuilder);
  private readonly notify = inject(NotifyService);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);
  readonly session = inject(SessionService);

  readonly humanize = humanize;
  readonly classOptions = options(CITY_CLASSES);
  readonly cities = signal<City[]>([]);
  readonly packs = signal<CountryPack[]>([]);
  readonly regions = signal<Region[]>([]);
  readonly loading = signal(true);
  readonly query = signal('');
  private readonly searches = new Subject<void>();

  readonly columns = ['name', 'state', 'aliases', 'classification', 'source'];
  private readonly editor = viewChild.required<TemplateRef<unknown>>('editor');
  private editorRef: MatDialogRef<unknown> | null = null;
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  readonly form = this.fb.nonNullable.group({
    countryCode: ['IN', Validators.required],
    stateCode: ['', Validators.required],
    name: ['', [Validators.required, Validators.maxLength(120)]],
    aliases: [''],
    district: [''],
    classification: [null as string | null],
    postalCodes: ['']
  });

  ngOnInit(): void {
    this.searches.pipe(
      debounceTime(200),
      switchMap(() => {
        this.loading.set(true);
        return this.api.cities({ q: this.query(), limit: 200 });
      }),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: (rows) => {
        this.cities.set(rows);
        this.loading.set(false);
      },
      error: (error) => {
        this.loading.set(false);
        this.notify.error(`Could not load cities. ${errorMessage(error)}`);
      }
    });
    this.searches.next();
    this.api.countryPacks().subscribe((packs) => this.packs.set(packs));
    this.loadRegions('IN');
  }

  onSearch(value: string): void {
    this.query.set(value);
    this.searches.next();
  }

  loadRegions(country: string): void {
    this.form.controls.stateCode.setValue('');
    this.api.regions(country).subscribe((regions) => this.regions.set(regions));
  }

  openNew(): void {
    this.form.reset({ countryCode: this.form.controls.countryCode.value });
    this.formError.set(null);
    this.editorRef = this.dialog.open(this.editor(), { width: 'min(42rem, 95vw)', maxWidth: '95vw', autoFocus: 'first-tabbable' });
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
    const list = (text: string) => text.split(',').map((s) => s.trim()).filter(Boolean);
    this.saving.set(true);
    this.formError.set(null);
    this.api.createCity({
      ...v, aliases: list(v.aliases), postalCodes: list(v.postalCodes), district: v.district || null
    }).subscribe({
      next: (city) => {
        this.saving.set(false);
        this.closeEditor();
        this.notify.success(`Added ${city.name}`);
        this.searches.next();
      },
      error: (error) => {
        this.saving.set(false);
        this.formError.set(errorMessage(error));
      }
    });
  }
}
