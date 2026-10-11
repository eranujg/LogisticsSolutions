import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { HttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { authInterceptor } from './core/auth/auth.interceptor';
import { AuthService } from './core/auth/auth.service';
import { Me, SessionService } from './core/session/session.service';
import { Shell } from './layout/shell';

const clerk: Me = {
  tenantId: 't1', tenantCode: 'demo', userId: 'u1', fullName: 'Demo Clerk', email: 'clerk@demo.local',
  permissions: ['party.view', 'party.create', 'city.view'], locationIds: [], developmentMode: false
};

class FakeAuth {
  token: string | null = 'token-123';
  signInCalls = 0;
  accessToken() { return this.token; }
  isSignedIn() { return true; }
  signIn() { this.signInCalls++; return Promise.resolve(); }
  signOut() { return Promise.resolve(); }
}

describe('Session and permissions', () => {
  let http: HttpTestingController;
  let auth: FakeAuth;

  beforeEach(() => {
    auth = new FakeAuth();
    TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth }
      ]
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('checks permissions, with * meaning everything (development mode)', async () => {
    const session = TestBed.inject(SessionService);
    const loading = session.load();
    http.expectOne('/api/v1/me').flush(clerk);
    await loading;

    expect(session.can('party.create')).toBe(true);
    expect(session.can('party.delete')).toBe(false);

    session.me.set({ ...clerk, permissions: ['*'] });
    expect(session.can('user.delete')).toBe(true);
  });

  it('shows only menu items the user may open', async () => {
    const fixture = TestBed.createComponent(Shell);
    fixture.detectChanges();
    http.expectOne('/api/v1/me').flush(clerk);
    await fixture.whenStable();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).querySelector('nav')!.textContent!;
    expect(text).toContain('Clients and parties');
    expect(text).toContain('Cities');
    expect(text).not.toContain('Users');
    expect(text).not.toContain('Company');
  });

  it('shows why access is blocked when the account is not set up', async () => {
    const fixture = TestBed.createComponent(Shell);
    fixture.detectChanges();
    http.expectOne('/api/v1/me').flush(
      { detail: 'No user is set up for your login in company demo.' }, { status: 403, statusText: 'Forbidden' });
    await fixture.whenStable();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No user is set up for your login');
  });

  it('adds the access token to API calls only, and signs in again on 401', () => {
    const client = TestBed.inject(HttpClient);

    client.get('/api/v1/parties').subscribe({ error: () => undefined });
    const apiCall = http.expectOne('/api/v1/parties');
    expect(apiCall.request.headers.get('Authorization')).toBe('Bearer token-123');
    apiCall.flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(auth.signInCalls).toBe(1);

    client.get('https://example.com/data').subscribe();
    const other = http.expectOne('https://example.com/data');
    expect(other.request.headers.has('Authorization')).toBe(false);
    other.flush({});
  });
});
