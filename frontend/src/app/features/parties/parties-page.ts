import { Component, DestroyRef, inject, OnInit, signal, TemplateRef, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { LowerCasePipe } from '@angular/common';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subject, debounceTime, switchMap } from 'rxjs';
import { ApiService, errorMessage } from '../../core/api/api.service';
import { DuplicateMatch, Party } from '../../core/api/models';
import { DeleteDialog } from '../../core/ui/delete-dialog';
import { NotifyService } from '../../core/ui/notify.service';
import { ACCOUNT_TYPES, BILLING_CYCLES, PARTY_ROLES, PAYMENT_TYPES, humanize, options } from '../../core/ui/labels';
import { SessionService } from '../../core/session/session.service';

/** Clients, consignors, consignees and bill-to parties. */
@Component({
  selector: 'tms-parties-page',
  imports: [
    ReactiveFormsModule, FormsModule, LowerCasePipe, MatTableModule, MatButtonModule, MatDialogModule, MatFormFieldModule,
    MatInputModule, MatSelectModule, MatIconModule, MatProgressBarModule, MatTooltipModule
  ],
  templateUrl: './parties-page.html'
})
export class PartiesPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly fb = inject(FormBuilder);
  private readonly notify = inject(NotifyService);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);
  readonly session = inject(SessionService);

  readonly humanize = humanize;
  readonly roleOptions = options(PARTY_ROLES);
  readonly accountOptions = options(ACCOUNT_TYPES);
  readonly paymentOptions = options(PAYMENT_TYPES);
  readonly cycleOptions = options(BILLING_CYCLES);

  readonly parties = signal<Party[]>([]);
  readonly loading = signal(true);
  readonly search = signal('');
  readonly roleFilter = signal<string | null>(null);
  private readonly searches = new Subject<void>();

  readonly columns = ['code', 'name', 'roles', 'mobile', 'gstin', 'account', 'actions'];
  private readonly editor = viewChild.required<TemplateRef<unknown>>('editor');
  private editorRef: MatDialogRef<unknown> | null = null;

  readonly editing = signal<Party | null>(null);
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);
  readonly duplicates = signal<DuplicateMatch[]>([]);


  readonly form = this.fb.nonNullable.group({
    legalName: ['', [Validators.required, Validators.maxLength(200)]],
    tradeName: [''],
    roles: [['CONSIGNOR'] as string[], Validators.required],
    mobile: ['', Validators.pattern(/^\+?[0-9]{7,15}$/)],
    email: ['', Validators.email],
    gstin: ['', Validators.pattern(/^[0-9]{2}[A-Za-z0-9]{13}$/)],
    accountType: ['CASH'],
    defaultPaymentType: [null as string | null],
    billingCycle: [null as string | null],
    creditLimit: [null as number | null],
    creditDays: [null as number | null],
    notes: ['']
  });

  ngOnInit(): void {
    this.searches.pipe(
      debounceTime(250),
      switchMap(() => {
        this.loading.set(true);
        return this.api.parties({ q: this.search(), role: this.roleFilter(), limit: 200 });
      }),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: (rows) => {
        this.parties.set(rows);
        this.loading.set(false);
      },
      error: (error) => {
        this.loading.set(false);
        this.notify.error(`Could not load parties. ${errorMessage(error)}`);
      }
    });
    this.searches.next();
  }

  onSearch(value: string): void {
    this.search.set(value);
    this.searches.next();
  }

  onRoleFilter(value: string | null): void {
    this.roleFilter.set(value);
    this.searches.next();
  }

  openNew(): void {
    this.editing.set(null);
    this.form.reset();
    this.duplicates.set([]);
    this.formError.set(null);
    this.openEditor();
  }

  openEdit(party: Party): void {
    this.editing.set(party);
    this.duplicates.set([]);
    this.formError.set(null);
    this.form.reset({
      legalName: party.legalName,
      tradeName: party.tradeName ?? '',
      roles: party.roles,
      mobile: party.mobile ?? '',
      email: party.email ?? '',
      gstin: party.taxRegistrations.find((t) => t.taxType === 'GSTIN')?.number ?? '',
      accountType: party.accountType,
      defaultPaymentType: party.defaultPaymentType,
      billingCycle: party.billingCycle,
      creditLimit: party.creditLimit,
      creditDays: party.creditDays,
      notes: party.notes ?? ''
    });
    this.openEditor();
  }

  private openEditor(): void {
    this.editorRef = this.dialog.open(this.editor(), { width: 'min(54rem, 95vw)', maxWidth: '95vw', autoFocus: 'first-tabbable' });
  }

  closeEditor(): void {
    this.editorRef?.close();
  }

  /** Warns about likely duplicates when mobile, GSTIN or name is entered for a new party. */
  checkDuplicates(): void {
    if (this.editing()) {
      return;
    }
    const v = this.form.getRawValue();
    if (!v.mobile && !v.gstin && !v.legalName) {
      return;
    }
    this.api.partyDuplicates({ mobile: v.mobile, taxNumber: v.gstin, name: v.legalName })
      .subscribe({ next: (matches) => this.duplicates.set(matches), error: () => this.duplicates.set([]) });
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const existing = this.editing();
    const otherTaxes = existing?.taxRegistrations.filter((t) => t.taxType !== 'GSTIN') ?? [];
    const body = {
      legalName: v.legalName.trim(),
      tradeName: v.tradeName || null,
      roles: v.roles,
      mobile: v.mobile || null,
      email: v.email || null,
      accountType: v.accountType,
      defaultPaymentType: v.defaultPaymentType,
      billingCycle: v.billingCycle,
      creditLimit: v.creditLimit,
      creditDays: v.creditDays,
      notes: v.notes || null,
      taxRegistrations: [
        ...otherTaxes.map((t) => ({ taxType: t.taxType, number: t.number, stateCode: t.stateCode })),
        ...(v.gstin ? [{ taxType: 'GSTIN', number: v.gstin }] : [])
      ]
    };
    this.saving.set(true);
    this.formError.set(null);
    this.api.saveParty(existing?.id ?? null, body).subscribe({
      next: (party) => {
        this.saving.set(false);
        this.closeEditor();
        this.notify.success(`${existing ? 'Updated' : 'Added'} ${party.code} ${party.legalName}`);
        this.searches.next();
      },
      error: (error) => {
        this.saving.set(false);
        this.formError.set(errorMessage(error));
      }
    });
  }

  askDelete(party: Party): void {
    DeleteDialog.ask(this.dialog, party.legalName).subscribe((reason) => {
      if (!reason) {
        return;
      }
      this.api.softDelete('parties', party.id, reason).subscribe({
        next: () => {
          this.notify.success(`${party.legalName} moved to the recycle bin`);
          this.searches.next();
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
