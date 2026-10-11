import { Component, computed, inject, OnInit } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/session/session.service';

interface NavItem {
  label: string;
  path: string;
  icon: string;
  permission: string | null;
  /** Highlight only on this exact path (e.g. the register, not its child pages). */
  exact?: boolean;
}

interface NavGroup {
  label: string;
  items: NavItem[];
}

const NAV: NavGroup[] = [
  {
    label: 'Daily work',
    items: [
      { label: 'Overview', path: '/', icon: 'home', permission: null },
      { label: 'Book GR', path: '/consignments/new', icon: 'add_box', permission: 'gr.create' },
      { label: 'GR register', path: '/consignments', icon: 'local_shipping', permission: 'gr.view', exact: true },
      { label: 'Rate calculator', path: '/rate-calculator', icon: 'calculate', permission: 'rate_card.view' }
    ]
  },
  {
    label: 'Masters',
    items: [
      { label: 'Clients and parties', path: '/parties', icon: 'handshake', permission: 'party.view' },
      { label: 'Rate cards', path: '/rate-cards', icon: 'sell', permission: 'rate_card.view' },
      { label: 'Offices and locations', path: '/locations', icon: 'apartment', permission: 'location.view' },
      { label: 'Cities', path: '/cities', icon: 'location_on', permission: 'city.view' }
    ]
  },
  {
    label: 'Setup',
    items: [
      { label: 'Company', path: '/company', icon: 'badge', permission: 'company.view' },
      { label: 'Charge heads', path: '/charge-heads', icon: 'receipt_long', permission: 'rate_card.view' },
      { label: 'Users', path: '/users', icon: 'group', permission: 'user.view' },
      { label: 'Roles and permissions', path: '/roles', icon: 'admin_panel_settings', permission: 'role.view' }
    ]
  }
];

@Component({
  selector: 'tms-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './shell.html',
  styleUrl: './shell.scss'
})
export class Shell implements OnInit {
  readonly session = inject(SessionService);
  private readonly auth = inject(AuthService);

  readonly nav = computed(() => {
    this.session.me();
    return NAV.map((group) => ({
      ...group,
      items: group.items.filter((item) => !item.permission || this.session.can(item.permission))
    })).filter((group) => group.items.length > 0);
  });

  readonly initials = computed(() => {
    const name = this.session.me()?.fullName ?? '';
    return name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]?.toUpperCase()).join('');
  });

  ngOnInit(): void {
    void this.session.load();
  }

  signOut(): void {
    void this.auth.signOut();
  }
}
