import { Injectable, computed, signal } from '@angular/core';
import { User, UserManager, WebStorageStateStore } from 'oidc-client-ts';
import { appSettings } from '../config';

/**
 * Sign-in with the identity provider (Keycloak locally, Cognito in AWS) using the
 * authorization code flow with PKCE. Tokens are kept in session storage, so they
 * are cleared when the browser tab closes.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly manager = new UserManager({
    authority: appSettings.auth.authority,
    client_id: appSettings.auth.clientId,
    scope: appSettings.auth.scope,
    redirect_uri: `${window.location.origin}/auth/callback`,
    post_logout_redirect_uri: `${window.location.origin}/`,
    response_type: 'code',
    automaticSilentRenew: true,
    userStore: new WebStorageStateStore({ store: window.sessionStorage })
  });

  private readonly user = signal<User | null>(null);

  readonly isSignedIn = computed(() => {
    const user = this.user();
    return !!user && !user.expired;
  });

  constructor() {
    this.manager.events.addUserLoaded((user) => this.user.set(user));
    this.manager.events.addUserUnloaded(() => this.user.set(null));
    this.manager.events.addSilentRenewError(() => this.user.set(null));
  }

  /** Loads a stored session, if any. Called once at app start. */
  async init(): Promise<void> {
    const user = await this.manager.getUser();
    this.user.set(user && !user.expired ? user : null);
  }

  accessToken(): string | null {
    const user = this.user();
    return user && !user.expired ? user.access_token : null;
  }

  /** Sends the browser to the login page, returning to `returnUrl` afterwards. */
  signIn(returnUrl = window.location.pathname): Promise<void> {
    return this.manager.signinRedirect({ state: { returnUrl } });
  }

  /** Completes sign-in on /auth/callback and returns where the user was going. */
  async completeSignIn(): Promise<string> {
    const user = await this.manager.signinRedirectCallback();
    this.user.set(user);
    const state = user.state as { returnUrl?: string } | undefined;
    const returnUrl = state?.returnUrl ?? '/';
    // Only same-app paths, never an external URL
    return returnUrl.startsWith('/') && !returnUrl.startsWith('//') ? returnUrl : '/';
  }

  async signOut(): Promise<void> {
    await this.manager.signoutRedirect();
  }
}
