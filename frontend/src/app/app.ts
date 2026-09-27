import { Component, OnInit, signal } from '@angular/core';

@Component({
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App implements OnInit {
  status = signal('Checking backend...');

  async ngOnInit() {
    try {
      const res = await fetch('/api/v1/system/info');
      const data = await res.json();
      this.status.set(`Backend OK | Database time: ${data.databaseTime} | Tenants: ${data.tenantCount}`);
    } catch {
      this.status.set('Backend not reachable');
    }
  }
}
