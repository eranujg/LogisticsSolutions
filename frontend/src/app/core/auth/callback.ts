import { Component, inject, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from './auth.service';

@Component({
  selector: 'tms-auth-callback',
  template: `
    <main class="auth-message">
      @if (error()) {
        <h1>Sign-in did not finish</h1>
        <p>{{ error() }}</p>
        <button type="button" (click)="retry()">Sign in again</button>
      } @else {
        <p>Signing you in…</p>
      }
    </main>
  `
})
export class AuthCallback implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  readonly error = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    try {
      const returnUrl = await this.auth.completeSignIn();
      await this.router.navigateByUrl(returnUrl, { replaceUrl: true });
    } catch {
      this.error.set('The login link has expired or was already used.');
    }
  }

  retry(): void {
    void this.auth.signIn('/');
  }
}
