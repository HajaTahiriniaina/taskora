import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiService, PushDevice } from './api.service';
import { NotificationService } from './notification.service';

/**
 * unsupported: no Web Push in this browser (or iPhone outside the Home Screen app)
 * denied: blocked in the browser settings · off: not enabled yet · on: banners active
 */
export type PushState = 'unsupported' | 'denied' | 'off' | 'on' | 'unconfigured';

/**
 * Browser notifications (Web Push). The service worker (/push-sw.js) shows the banners sent by the
 * API; this service registers it, subscribes this browser (after an explicit click: browsers only
 * allow the permission prompt on a user gesture) and keeps the subscription tied to the logged-in user.
 */
@Injectable({ providedIn: 'root' })
export class PushService {
  private api = inject(ApiService);
  private router = inject(Router);
  private notifs = inject(NotificationService);

  readonly state = signal<PushState>('off');
  readonly busy = signal(false);
  readonly devices = signal<PushDevice[]>([]);
  /** Hash of this browser's endpoint, to mark "this device" in the list */
  readonly currentHash = signal<string | null>(null);
  private registration: ServiceWorkerRegistration | null = null;
  private started = false;

  get supported(): boolean {
    return typeof window !== 'undefined' && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window && window.isSecureContext;
  }

  /** At login / app start: registers the worker, re-links an existing subscription to the current user. */
  async start() {
    if (this.started) return;
    this.started = true;
    if (!this.supported) { this.state.set('unsupported'); return; }
    if (Notification.permission === 'denied') { this.state.set('denied'); return; }
    navigator.serviceWorker.addEventListener('message', e => this.onWorkerMessage(e));
    try {
      this.registration = await navigator.serviceWorker.register('/push-sw.js', { scope: '/' });
      const sub = await this.registration.pushManager.getSubscription();
      if (sub && Notification.permission === 'granted') {
        await firstValueFrom(this.api.pushSubscribe(sub.toJSON(), deviceLabel())); // same browser, maybe another account
        await this.setCurrent(sub);
        this.state.set('on');
      } else {
        this.state.set('off');
      }
    } catch {
      this.state.set('unsupported'); // service workers blocked (private window, embedded browser…)
    }
  }

  /** "Activer": asks the permission (must run from a click) then subscribes this browser. */
  async enable(): Promise<boolean> {
    if (!this.supported) return false;
    this.busy.set(true);
    try {
      const config = await firstValueFrom(this.api.pushConfig());
      if (!config.configured) { this.state.set('unconfigured'); return false; }
      const permission = await Notification.requestPermission();
      if (permission !== 'granted') { this.state.set(permission === 'denied' ? 'denied' : 'off'); return false; }
      const reg = this.registration ?? (this.registration = await navigator.serviceWorker.register('/push-sw.js', { scope: '/' }));
      await navigator.serviceWorker.ready;
      const sub = (await reg.pushManager.getSubscription())
        ?? (await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: base64UrlToBytes(config.publicKey) }));
      await firstValueFrom(this.api.pushSubscribe(sub.toJSON(), deviceLabel()));
      await this.setCurrent(sub);
      this.state.set('on');
      await this.loadDevices();
      return true;
    } finally {
      this.busy.set(false);
    }
  }

  /** "Désactiver" on this browser. */
  async disable() {
    this.busy.set(true);
    try {
      const sub = await this.registration?.pushManager.getSubscription();
      if (sub) {
        await firstValueFrom(this.api.pushUnsubscribe(sub.endpoint)).catch(() => undefined);
        await sub.unsubscribe();
      }
      this.state.set(this.supported ? 'off' : 'unsupported');
      this.currentHash.set(null);
      await this.loadDevices();
    } finally {
      this.busy.set(false);
    }
  }

  /** Logout: this browser stops receiving the previous user's notifications. */
  async forget() {
    try {
      const sub = await this.registration?.pushManager.getSubscription();
      if (sub) {
        await firstValueFrom(this.api.pushUnsubscribe(sub.endpoint)).catch(() => undefined);
        await sub.unsubscribe();
      }
    } catch { /* ignore */ }
    this.state.set(this.supported ? 'off' : 'unsupported');
    this.devices.set([]);
    this.started = false;
  }

  async test() {
    const sub = await this.registration?.pushManager.getSubscription();
    if (sub) await firstValueFrom(this.api.pushTest(sub.endpoint));
  }

  async loadDevices() {
    try {
      const config = await firstValueFrom(this.api.pushConfig());
      this.devices.set(config.devices);
      if (!config.configured && this.state() === 'off') this.state.set('unconfigured');
    } catch { /* ignore */ }
  }

  async removeDevice(d: PushDevice) {
    if (d.endpointHash === this.currentHash()) { await this.disable(); return; }
    await firstValueFrom(this.api.pushRemoveDevice(d.id));
    this.devices.update(list => list.filter(x => x.id !== d.id));
  }

  private async setCurrent(sub: PushSubscription) {
    const bytes = new TextEncoder().encode(sub.endpoint);
    const digest = await crypto.subtle.digest('SHA-256', bytes);
    this.currentHash.set(Array.from(new Uint8Array(digest)).map(b => b.toString(16).padStart(2, '0')).join(''));
  }

  private onWorkerMessage(e: MessageEvent) {
    const data = e.data as { type?: string; url?: string } | null;
    if (data?.type === 'taskora-push') this.notifs.load();
    if (data?.type === 'taskora-open' && typeof data.url === 'string' && data.url.startsWith('/') && !data.url.startsWith('//')) {
      this.router.navigateByUrl(data.url);
    }
  }
}

function base64UrlToBytes(value: string): Uint8Array<ArrayBuffer> {
  const padded = (value + '='.repeat((4 - (value.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/');
  const raw = atob(padded);
  const out = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

/** "Chrome · macOS": shown in the list of devices. */
function deviceLabel(): string {
  const ua = navigator.userAgent;
  const browser = /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /Firefox\//.test(ua) ? 'Firefox'
    : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Navigateur';
  const os = /iPhone|iPad/.test(ua) ? 'iOS' : /Android/.test(ua) ? 'Android' : /Mac OS X/.test(ua) ? 'macOS'
    : /Windows/.test(ua) ? 'Windows' : /Linux/.test(ua) ? 'Linux' : '';
  return os ? `${browser} · ${os}` : browser;
}
