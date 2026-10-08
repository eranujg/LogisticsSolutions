import { Injectable, inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

/** Short confirmations and errors at the bottom of the screen. */
@Injectable({ providedIn: 'root' })
export class NotifyService {
  private readonly snackBar = inject(MatSnackBar);

  success(message: string): void {
    this.snackBar.open(message, 'OK', { duration: 3500, panelClass: 'tms-snack-success' });
  }

  error(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 8000, panelClass: 'tms-snack-error' });
  }
}
