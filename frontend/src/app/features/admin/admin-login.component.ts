import { ChangeDetectionStrategy, Component, computed, inject, OnDestroy, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { ActivatedRoute } from '@angular/router';

import { AdminApiService, TIMEOUT_STATUS } from '../../core/admin-api.service';
import { AdminApiError, describeThrottle } from '../../core/admin-models';

/**
 * The only place administrator credentials are entered. On success the router takes over to the
 * policies workspace; the credentials stay in the API service memory and are never stored.
 */
@Component({
  selector: 'app-admin-login',
  standalone: true,
  imports: [FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './admin-login.component.html',
  styleUrl: './admin-login.component.scss',
})
export class AdminLoginComponent implements OnDestroy {
  private readonly api = inject(AdminApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly username = signal('');
  readonly password = signal('');
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  /** Seconds left before another attempt is allowed; 0 when not throttled. */
  readonly retryIn = signal(0);
  private throttleStatus = 429;
  private timer: ReturnType<typeof setInterval> | null = null;
  readonly throttleMessage = computed(() =>
    this.retryIn() > 0
      ? describeThrottle({ status: this.throttleStatus, code: '', message: '', problems: [] }, this.retryIn())
      : null,
  );

  constructor() {
    if (this.api.loggedIn()) {
      const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') || '/overview';
      void this.router.navigateByUrl(returnUrl);
    }
  }

  async onLogin(): Promise<void> {
    if (this.retryIn() > 0) return;
    this.error.set(null);
    if (!this.username().trim() || !this.password()) {
      this.error.set('Enter the administrator username and password.');
      return;
    }
    this.busy.set(true);
    try {
      const result = await firstValueFrom(this.api.login(this.username(), this.password()));
      this.password.set('');
      if (result.ok) {
        this.username.set('');
        const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') || '/overview';
        await this.router.navigateByUrl(returnUrl);
      } else if (
        (result.error.status === 429 || result.error.status === 503) &&
        (result.error.retryAfterSeconds ?? 0) > 0
      ) {
        this.startCountdown(result.error.status, result.error.retryAfterSeconds!);
      } else {
        this.error.set(describeLoginFailure(result.error));
      }
    } finally {
      this.busy.set(false);
    }
  }

  ngOnDestroy(): void {
    this.stopTimer();
  }

  private startCountdown(status: number, seconds: number): void {
    this.stopTimer();
    this.throttleStatus = status;
    this.retryIn.set(Math.ceil(seconds));
    this.timer = setInterval(() => {
      this.retryIn.update((n) => Math.max(0, n - 1));
      if (this.retryIn() === 0) this.stopTimer();
    }, 1000);
  }

  private stopTimer(): void {
    if (this.timer !== null) clearInterval(this.timer);
    this.timer = null;
  }
}

/**
 * Turns a login failure into one operator-actionable sentence. A 401 is a credential problem; a
 * timeout or a transport failure is not, and telling someone to retype a correct password would be
 * wrong advice.
 */
export function describeLoginFailure(error: AdminApiError): string {
  const throttle = describeThrottle(error);
  if (throttle) return throttle;
  if (error.status === 401) return 'Wrong administrator username or password.';
  if (error.status === 403) return 'That account is not an administrator.';
  if (error.status === TIMEOUT_STATUS) return error.message;
  if (error.status === 0) return 'Backend unreachable. Is the API running?';
  return error.message;
}
