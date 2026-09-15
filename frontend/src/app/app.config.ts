import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { environment } from '../environments/environment';
import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth-interceptor';
import { APP_CONFIG } from './core/config/app-config';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // `withComponentInputBinding` binds `:id` route parameters straight to a
    // component `input()`, so detail screens take their identity as an input
    // and stay trivially testable with `setInput`.
    provideRouter(routes, withComponentInputBinding()),
    // The environment is supplied as an injected value rather than imported
    // directly by services, so tests override it with a plain provider.
    { provide: APP_CONFIG, useValue: environment },
    provideHttpClient(withInterceptors([authInterceptor])),
  ],
};
