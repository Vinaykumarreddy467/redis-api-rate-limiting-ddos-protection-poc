import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { catchError, map, timeout } from 'rxjs/operators';

import { API_CONFIG } from './api-config';
import { DemoRoute } from './demo-catalog';
import { RejectionHeaders, ResponseEntry } from './models';

export interface DemoRequestResult {
  status: number;
  headers: RejectionHeaders;
  /** Full response body for pretty-printing, null if not JSON or empty. */
  body: unknown | null;
  /** Extracted message field for quick display. */
  message: string | null;
  /** Set when the request never produced a response, e.g. offline or cancelled. */
  transportError: string | null;
}

export interface Credentials {
  username: string;
  password: string;
}

const REQUEST_TIMEOUT_MS = 10_000;
const EMPTY_HEADERS: RejectionHeaders = { retryAfter: null, limit: null, remaining: null, policy: null };

/**
 * Sends one demo request and reduces the response to what the console may display.
 *
 * Nothing here retries: a 429 is a result, not a transient error. Rate-limit headers are read from
 * the real HttpResponse, so they survive the 429 error path.
 */
@Injectable({ providedIn: 'root' })
export class DemoRequestService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(API_CONFIG);

  send(route: DemoRoute, credentials: Credentials | null): Observable<DemoRequestResult> {
    let headers = new HttpHeaders({ Accept: 'application/json' });
    if (credentials) {
      headers = headers.set('Authorization', 'Basic ' + this.encodeBasic(credentials));
    }
    // Verified: /api/login reads a query parameter, so no route sends a body.
    // responseType 'text' keeps the raw body for the 429 path, where HttpClient would otherwise
    // throw an HttpErrorResponse instead of surfacing the rate-limit headers.
    return this.http
      .request(route.method, `${this.config.baseUrl}${route.path}`, {
        headers,
        responseType: 'text' as const,
        observe: 'response' as const,
      })
      .pipe(
        timeout(REQUEST_TIMEOUT_MS),
        map((response) => this.fromResponse(response.status, response.headers, response.body as string | null)),
        catchError((error) => of(this.fromError(error as { status?: number; error?: unknown; name?: string }))),
      );
  }

  private fromResponse(status: number, headers: HttpHeaders, body: string | null): DemoRequestResult {
    const parsedBody = this.parseBody(body);
    return {
      status,
      headers: {
        retryAfter: headers.get('Retry-After'),
        limit: headers.get('X-RateLimit-Limit'),
        remaining: headers.get('X-RateLimit-Remaining'),
        policy: headers.get('X-RateLimit-Policy'),
      },
      body: parsedBody,
      message: this.extractMessage(parsedBody),
      transportError: null,
    };
  }

  /**
   * A 429 (and any other non-2xx) arrives here as an HttpErrorResponse. Its headers still carry the
   * rate-limit information, so they are read rather than discarded.
   */
  private fromError(error: { status?: number; error?: unknown; name?: string; headers?: HttpHeaders }): DemoRequestResult {
    const status = error?.status ?? 0;
    const text = typeof error?.error === 'string' ? error.error : '';
    const headers = error?.headers;
    const parsedBody = this.parseBody(text);
    return {
      status,
      headers: headers
        ? {
            retryAfter: headers.get('Retry-After'),
            limit: headers.get('X-RateLimit-Limit'),
            remaining: headers.get('X-RateLimit-Remaining'),
            policy: headers.get('X-RateLimit-Policy'),
          }
        : EMPTY_HEADERS,
      body: parsedBody,
      message: this.extractMessage(parsedBody),
      transportError: status === 0 ? (error?.name ?? 'network-error') : null,
    };
  }

  private parseBody(text: string | null): unknown | null {
    if (!text || !text.trim()) return null;
    try {
      return JSON.parse(text);
    } catch {
      return text; // Return raw text if not JSON
    }
  }

  private extractMessage(body: unknown | null): string | null {
    if (!body || typeof body !== 'object') return null;
    const obj = body as { message?: unknown };
    return typeof obj.message === 'string' ? obj.message : null;
  }

  private encodeBasic(credentials: Credentials): string {
    return btoa(`${credentials.username}:${credentials.password}`);
  }
}
