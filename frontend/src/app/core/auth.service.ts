import { computed, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { tap } from 'rxjs';

import { ApiService } from './api.service';
import { User } from './models';

const TOKEN_KEY = 'taskora.token';
const USER_KEY = 'taskora.user';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private api = inject(ApiService);
  private router = inject(Router);

  readonly token = signal<null | string>(this.read(TOKEN_KEY));
  readonly user = signal<null | User>(JSON.parse(this.read(USER_KEY) ?? 'null'));
  readonly isLoggedIn = computed(() => !!this.token() && !this.isExpired(this.token()!));

  login(email: string, password: string) {
    return this.api.login(email, password).pipe(tap((r) => this.store(r.token, r.user)));
  }

  register(name: string, email: string, password: string) {
    return this.api.register(name, email, password).pipe(tap((r) => this.store(r.token, r.user)));
  }

  refreshProfile() {
    this.api.me().subscribe((u) => this.setUser(u));
  }

  setUser(user: User) {
    this.user.set(user);
    this.write(USER_KEY, JSON.stringify(user));
  }

  logout() {
    this.token.set(null);
    this.user.set(null);
    try {
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(USER_KEY);
    } catch {
      /* ignore */
    }
    this.router.navigateByUrl('/login');
  }

  private store(token: string, user: User) {
    this.token.set(token);
    this.write(TOKEN_KEY, token);
    this.setUser(user);
  }

  private isExpired(token: string): boolean {
    try {
      const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
      return payload.exp * 1000 < Date.now();
    } catch {
      return true;
    }
  }

  private read(key: string): null | string {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  }

  private write(key: string, value: string) {
    try {
      localStorage.setItem(key, value);
    } catch {
      /* ignore */
    }
  }
}
