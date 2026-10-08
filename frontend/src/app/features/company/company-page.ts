import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ApiService, errorMessage } from '../../core/api/api.service';
import { Company, CountryPack, Region } from '../../core/api/models';
import { SessionService } from '../../core/session/session.service';
import { BUSINESS_TYPES, Option, humanize, options } from '../../core/ui/labels';
import { NotifyService } from '../../core/ui/notify.service';

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October',
  'November', 'December'];

/**
 * Company setup. The country decides currency, financial year, tax IDs and
 * ownership types (from the country pack); all can be changed.
 */
@Component({
  selector: 'tms-company-page',
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule],
  templateUrl: './company-page.html'
})
export class CompanyPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly fb = inject(FormBuilder);
  private readonly notify = inject(NotifyService);
  readonly session = inject(SessionService);

  readonly businessOptions = options(BUSINESS_TYPES);
  readonly monthOptions: Option[] = MONTHS.map((label, i) => ({ label, value: String(i + 1) }));

  readonly packs = signal<CountryPack[]>([]);
  readonly regions = signal<Region[]>([]);
  readonly ownershipOptions = signal<Option[]>([]);
  readonly taxTypes = signal<string[]>([]);
  readonly regionLabel = signal('State');
  readonly company = signal<Company | null>(null);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  readonly form = this.fb.nonNullable.group({
    legalName: ['', [Validators.required, Validators.maxLength(200)]],
    tradeName: [''],
    countryCode: ['IN', Validators.required],
    stateCode: [null as string | null],
    businessTypes: [['TRANSPORTER'] as string[], Validators.required],
    ownershipType: ['', Validators.required],
    baseCurrency: ['INR', [Validators.required, Validators.pattern(/^[A-Z]{3}$/)]],
    fyStartMonth: ['4', Validators.required],
    email: ['', Validators.email],
    phone: [''],
    website: [''],
    addressLine1: [''],
    addressLine2: [''],
    cityName: [''],
    postalCode: [''],
    taxes: this.fb.nonNullable.group({} as Record<string, never>)
  });

  ngOnInit(): void {
    this.api.countryPacks().subscribe((packs) => {
      this.packs.set(packs);
      this.api.companies().subscribe({
        next: (companies) => {
          const company = companies[0] ?? null;
          this.company.set(company);
          this.applyCountry(company?.countryCode ?? 'IN', !company);
          if (company) {
            this.form.patchValue({ ...company, fyStartMonth: String(company.fyStartMonth),
              tradeName: company.tradeName ?? '', email: company.email ?? '', phone: company.phone ?? '',
              website: company.website ?? '', addressLine1: company.addressLine1 ?? '',
              addressLine2: company.addressLine2 ?? '', cityName: company.cityName ?? '',
              postalCode: company.postalCode ?? '' });
            for (const tax of company.taxRegistrations) {
              this.form.controls.taxes.get(tax.taxType)?.setValue(tax.number as never);
            }
          }
          if (!this.session.can(company ? 'company.edit' : 'company.create')) {
            this.form.disable();
          }
          this.loading.set(false);
        },
        error: (error) => {
          this.loading.set(false);
          this.formError.set(errorMessage(error));
        }
      });
    });
  }

  /** Switches the form to a country's defaults. Keeps entered values when `resetDefaults` is false. */
  applyCountry(code: string, resetDefaults = true): void {
    const pack = this.packs().find((p) => p.code === code);
    if (!pack) {
      return;
    }
    this.form.controls.countryCode.setValue(code);
    this.regionLabel.set(pack.regionLabel);
    this.ownershipOptions.set(options(pack.ownershipTypes));
    this.taxTypes.set(pack.companyTaxIdTypes);

    const taxes = this.form.controls.taxes as unknown as import('@angular/forms').FormGroup;
    for (const name of Object.keys(taxes.controls)) {
      taxes.removeControl(name);
    }
    for (const type of pack.companyTaxIdTypes) {
      taxes.addControl(type, this.fb.nonNullable.control(''));
    }
    if (resetDefaults) {
      this.form.patchValue({ baseCurrency: pack.currencyCode, fyStartMonth: String(pack.fyStartMonth), stateCode: null,
        ownershipType: '' });
    }
    this.api.regions(code).subscribe((regions) => this.regions.set(regions));
  }

  taxLabel(type: string): string {
    return humanize(type);
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const taxes = v.taxes as Record<string, string>;
    const state = v.stateCode;
    const body = {
      ...v,
      taxes: undefined,
      fyStartMonth: Number(v.fyStartMonth),
      taxRegistrations: Object.entries(taxes)
        .filter(([, number]) => number?.trim())
        .map(([taxType, number]) => ({ taxType, number: number.trim(), stateCode: taxType === 'GSTIN' ? state : null }))
    };
    this.saving.set(true);
    this.formError.set(null);
    this.api.saveCompany(this.company()?.id ?? null, body).subscribe({
      next: (company) => {
        this.saving.set(false);
        this.company.set(company);
        this.form.markAsPristine();
        this.notify.success(`Saved ${company.legalName}`);
      },
      error: (error) => {
        this.saving.set(false);
        this.formError.set(errorMessage(error));
      }
    });
  }

  invalid(name: string): boolean {
    const control = this.form.get(name);
    return !!control && control.invalid && control.touched;
  }
}
