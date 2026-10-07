import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { App } from './app';

describe('App', () => {
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('should render system info from the backend', async () => {
    const fixture = TestBed.createComponent(App);
    httpTesting
      .expectOne('/api/v1/system/info')
      .flush({ app: 'tms-backend', databaseTime: '2026-10-07 10:00:00+00', tenantCount: 2 });
    await fixture.whenStable();

    const text = (fixture.nativeElement as HTMLElement).textContent;
    expect(text).toContain('Backend OK');
    expect(text).toContain('tms-backend');
  });

  it('should show an error when the backend is unreachable', async () => {
    const fixture = TestBed.createComponent(App);
    httpTesting
      .expectOne('/api/v1/system/info')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Backend not reachable');
  });
});
