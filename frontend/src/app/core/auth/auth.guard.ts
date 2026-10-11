import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { AuthService } from './auth.service';

/** Signed-in users only; others go to the login page and come back here. */
export const authGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(AuthService);
  if (auth.isSignedIn()) {
    return true;
  }
  await auth.signIn(state.url);
  return false;
};
