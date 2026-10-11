import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Observable } from 'rxjs';

interface DeleteDialogData {
  itemName: string;
  action: string;
  note: string;
}

/**
 * Asks for a reason before a record is removed. The reason is stored with the
 * record and shown in the recycle bin and audit log.
 */
@Component({
  selector: 'tms-delete-dialog',
  imports: [MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, FormsModule],
  template: `
    <h2 mat-dialog-title>{{ data.action }} {{ data.itemName }}</h2>
    <mat-dialog-content>
      <p>{{ data.note }}</p>
      <mat-form-field appearance="outline" class="full">
        <mat-label>Reason</mat-label>
        <input matInput [ngModel]="reason()" (ngModelChange)="reason.set($event)" placeholder="For example: duplicate entry"
               autocomplete="off" cdkFocusInitial />
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button mat-flat-button class="danger" [disabled]="!reason().trim()" (click)="ref.close(reason().trim())">
        {{ data.action }}
      </button>
    </mat-dialog-actions>
  `
})
export class DeleteDialog {
  readonly data = inject<DeleteDialogData>(MAT_DIALOG_DATA);
  readonly ref = inject(MatDialogRef<DeleteDialog, string>);
  readonly reason = signal('');

  /** Opens the dialog; emits the reason, or undefined if cancelled. */
  static ask(dialog: MatDialog, itemName: string, action = 'Delete',
             note = 'It moves to the recycle bin and can be restored from there.'): Observable<string | undefined> {
    return dialog.open<DeleteDialog, DeleteDialogData, string>(DeleteDialog, {
      data: { itemName, action, note },
      width: '28rem'
    }).afterClosed();
  }
}
