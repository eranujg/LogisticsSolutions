import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SystemInfo, SystemInfoService } from './core/system/system-info.service';

@Component({
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App {
  readonly info = signal<SystemInfo | null>(null);
  readonly error = signal<string | null>(null);

  constructor() {
    inject(SystemInfoService)
      .getInfo()
      .pipe(takeUntilDestroyed())
      .subscribe({
        next: (info) => this.info.set(info),
        error: () => this.error.set('Backend not reachable')
      });
  }
}
