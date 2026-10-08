import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionService } from './session.service';

/** Route guard: `canActivate: [permissionGuard('party.view')]`. */
export const permissionGuard = (permission: string): CanActivateFn => async () => {
  const session = inject(SessionService);
  if (!session.me()) {
    await session.load();
  }
  return session.can(permission) ? true : inject(Router).parseUrl('/');
};
