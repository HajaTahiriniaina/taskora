import { ApplicationConfig, LOCALE_ID, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { provideRouter, withComponentInputBinding, withViewTransitions } from '@angular/router';
import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import { routes } from './app.routes';
import { authInterceptor } from './core/auth.interceptor';

registerLocaleData(localeFr);

// Angular 22: zoneless change detection by default (no zone.js).
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding(), withViewTransitions()),
    // XHR backend (fetch is the default since Angular 22): fetch() cannot report upload progress, needed for the % bars.
    // Browser-only app (no SSR), so the SSR caveat of withXhr does not apply.
    provideHttpClient(withXhr(), withInterceptors([authInterceptor])),
    { provide: LOCALE_ID, useValue: 'fr-FR' },
  ],
};
