import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { ToastService } from './toast.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const toast = inject(ToastService);
  const token = auth.token();
  const isAuthCall = req.url.includes('/auth/');

  if (token && !isAuthCall) {
    req = req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
  }

  return next(req).pipe(
    catchError((err: HttpErrorResponse) => {
      if (err.status === 401 && !isAuthCall) {
        auth.logout();
        toast.show('Votre session a expiré, reconnectez-vous.', 'error');
      } else if (!isAuthCall && !req.url.includes('unread-count')) {
        toast.show(err.error?.error ?? err.error?.message ?? 'Une erreur est survenue.', 'error');
      }
      return throwError(() => err);
    }),
  );
};
