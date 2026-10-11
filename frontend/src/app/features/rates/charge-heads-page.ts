import { Component, inject, OnInit, signal, TemplateRef, viewChild } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ApiService, errorMessage } from '../../core/api/api.service';
import { ChargeHead } from '../../core/api/models';
import { DeleteDialog } from '../../core/ui/delete-dialog';
import { CHARGE_METHODS, EDIT_CONTROLS, humanize, options } from '../../core/ui/labels';
import { NotifyService } from '../../core/ui/notify.service';
import { SessionService } from '../../core/session/session.service';

/** Charges added to a GR besides freight, with their default values. */
@Component({
  selector: 'tms-charge-heads-page',
  imports: [
    ReactiveFormsModule, MatTableModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatCheckboxModule, MatIconModule, MatProgressBarModule, MatTooltipModule
  ],
  templateUrl: './charge-heads-page.html'
})
export class ChargeHeadsPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly fb = inject(FormBuilder);
  private readonly notify = inject(NotifyService);
  private readonly dialog = inject(MatDialog);
  readonly session = inject(SessionService);

  readonly humanize = humanize;
  readonly methodOptions = options(CHARGE_METHODS);
  readonly editOptions = options(EDIT_CONTROLS);

  readonly heads = signal<ChargeHead[]>([]);
  readonly loading = signal(true);
  readonly columns = ['code', 'name', 'method', 'value', 'auto', 'edit', 'actions'];

  private readonly editor = viewChild.required<TemplateRef<unknown>>('editor');
  private editorRef: MatDialogRef<unknown> | null = null;
  readonly editing = signal<ChargeHead | null>(null);
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  readonly form = this.fb.nonNullable.group({
    code: ['', [Validators.required, Validators.maxLength(30), Validators.pattern(/^[A-Za-z0-9_-]+$/)]],
    name: ['', [Validators.required, Validators.maxLength(100)]],
    calcMethod: ['FIXED', Validators.required],
    defaultValue: [0, Validators.min(0)],
    minAmount: [0, Validators.min(0)],
    isAuto: [false],
    editControl: ['FREE'],
    taxable: [true],
    taxCode: [''],
    sortOrder: [100],
    active: [true]
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api.chargeHeads().subscribe({
      next: (rows) => {
        this.heads.set(rows);
        this.loading.set(false);
      },
      error: (error) => {
        this.loading.set(false);
        this.notify.error(`Could not load charge heads. ${errorMessage(error)}`);
      }
    });
  }

  loadDefaults(): void {
    this.api.loadDefaultChargeHeads().subscribe({
      next: (rows) => {
        this.heads.set(rows);
        this.notify.success('Default charge heads added');
      },
      error: (error) => this.notify.error(errorMessage(error))
    });
  }

  valueLabel(head: ChargeHead): string {
    switch (head.calcMethod) {
      case 'RATE_CARD': return 'From rate card';
      case 'PERCENT_OF_FREIGHT':
      case 'PERCENT_OF_VALUE': return `${head.defaultValue}%`;
      case 'PER_PACKAGE': return `${head.defaultValue} / package`;
      case 'PER_KG': return `${head.defaultValue} / kg`;
      default: return String(head.defaultValue);
    }
  }

  openNew(): void {
    this.editing.set(null);
    this.formError.set(null);
    this.form.reset();
    this.form.controls.code.enable();
    this.form.controls.calcMethod.enable();
    this.open();
  }

  openEdit(head: ChargeHead): void {
    this.editing.set(head);
    this.formError.set(null);
    this.form.reset({
      code: head.code, name: head.name, calcMethod: head.calcMethod, defaultValue: head.defaultValue,
      minAmount: head.minAmount, isAuto: head.isAuto, editControl: head.editControl, taxable: head.taxable,
      taxCode: head.taxCode ?? '', sortOrder: head.sortOrder, active: head.active
    });
    if (head.isSystem) {
      this.form.controls.code.disable();
      this.form.controls.calcMethod.disable();
    } else {
      this.form.controls.code.enable();
      this.form.controls.calcMethod.enable();
    }
    this.open();
  }

  private open(): void {
    this.editorRef = this.dialog.open(this.editor(), { width: 'min(44rem, 95vw)', maxWidth: '95vw' });
  }

  close(): void {
    this.editorRef?.close();
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const head = this.editing();
    const body = {
      ...v,
      // The freight head keeps its method on the server; send a valid value for the form check.
      calcMethod: head?.isSystem ? 'FIXED' : v.calcMethod,
      taxCode: v.taxCode || null
    };
    this.saving.set(true);
    this.formError.set(null);
    this.api.saveChargeHead(head?.id ?? null, body).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.close();
        this.notify.success(`${head ? 'Updated' : 'Added'} ${saved.name}`);
        this.load();
      },
      error: (error) => {
        this.saving.set(false);
        this.formError.set(errorMessage(error));
      }
    });
  }

  askDelete(head: ChargeHead): void {
    DeleteDialog.ask(this.dialog, head.name).subscribe((reason) => {
      if (!reason) {
        return;
      }
      this.api.softDelete('charge-heads', head.id, reason).subscribe({
        next: () => {
          this.notify.success(`${head.name} moved to the recycle bin`);
          this.load();
        },
        error: (error) => this.notify.error(`Could not delete. ${errorMessage(error)}`)
      });
    });
  }
}
