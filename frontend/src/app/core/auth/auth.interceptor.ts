import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { appSettings } from '../config';
import { AuthService } from './auth.service';

/** Adds the access token to API calls and sends the user to sign in on 401. */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith(appSettings.apiBase)) {
    return next(request);
  }
  const auth = inject(AuthService);
  const token = auth.accessToken();
  const authorised = token ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : request;
  return next(authorised).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        void auth.signIn();
      }
      return throwError(() => error);
    })
  );
};
