import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { AuthCallback } from './core/auth/callback';
import { permissionGuard } from './core/session/permission.guard';
import { Shell } from './layout/shell';

export const routes: Routes = [
  { path: 'auth/callback', component: AuthCallback },
  {
    path: '',
    component: Shell,
    canActivate: [authGuard],
    children: [
      { path: '', title: 'Overview', loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard) },
      {
        path: 'company',
        title: 'Company',
        canActivate: [permissionGuard('company.view')],
        loadComponent: () => import('./features/company/company-page').then((m) => m.CompanyPage)
      },
      {
        path: 'locations',
        title: 'Offices and locations',
        canActivate: [permissionGuard('location.view')],
        loadComponent: () => import('./features/locations/locations-page').then((m) => m.LocationsPage)
      },
      {
        path: 'cities',
        title: 'Cities',
        canActivate: [permissionGuard('city.view')],
        loadComponent: () => import('./features/cities/cities-page').then((m) => m.CitiesPage)
      },
      {
        path: 'parties',
        title: 'Clients and parties',
        canActivate: [permissionGuard('party.view')],
        loadComponent: () => import('./features/parties/parties-page').then((m) => m.PartiesPage)
      },
      {
        path: 'rate-cards',
        title: 'Rate cards',
        canActivate: [permissionGuard('rate_card.view')],
        loadComponent: () => import('./features/rates/rate-cards-page').then((m) => m.RateCardsPage)
      },
      {
        path: 'rate-cards/:id',
        title: 'Rate card',
        canActivate: [permissionGuard('rate_card.view')],
        loadComponent: () => import('./features/rates/rate-card-editor').then((m) => m.RateCardEditor)
      },
      {
        path: 'rate-calculator',
        title: 'Rate calculator',
        canActivate: [permissionGuard('rate_card.view')],
        loadComponent: () => import('./features/rates/rate-calculator').then((m) => m.RateCalculator)
      },
      {
        path: 'charge-heads',
        title: 'Charge heads',
        canActivate: [permissionGuard('rate_card.view')],
        loadComponent: () => import('./features/rates/charge-heads-page').then((m) => m.ChargeHeadsPage)
      },
      {
        path: 'users',
        title: 'Users',
        canActivate: [permissionGuard('user.view')],
        loadComponent: () => import('./features/users/users-page').then((m) => m.UsersPage)
      },
      {
        path: 'roles',
        title: 'Roles and permissions',
        canActivate: [permissionGuard('role.view')],
        loadComponent: () => import('./features/roles/roles-page').then((m) => m.RolesPage)
      }
    ]
  },
  { path: '**', redirectTo: '' }
];
