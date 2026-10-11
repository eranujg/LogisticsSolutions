import { Component, computed, inject, OnInit, signal, TemplateRef, viewChild } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { forkJoin, of } from 'rxjs';
import { ApiService, errorMessage } from '../../core/api/api.service';
import { AppUser, Location, Role } from '../../core/api/models';
import { SessionService } from '../../core/session/session.service';
import { DeleteDialog } from '../../core/ui/delete-dialog';
import { USER_STATUSES, USER_TYPES, humanize, options } from '../../core/ui/labels';
import { NotifyService } from '../../core/ui/notify.service';

/**
 * People who use the system. Passwords are not set here: a user signs in with
 * the login service (Keycloak / Cognito) using the same email, and is linked on
 * first sign-in.
 */
@Component({
  selector: 'tms-users-page',
  imports: [
    ReactiveFormsModule, DatePipe, MatTableModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatIconModule, MatProgressBarModule, MatTooltipModule
  ],
  templateUrl: './users-page.html'
})
export class UsersPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly fb = inject(FormBuilder);
  private readonly notify = inject(NotifyService);
  private readonly dialog = inject(MatDialog);
  readonly session = inject(SessionService);

  readonly humanize = humanize;
  readonly typeOptions = options(USER_TYPES);
  readonly statusOptions = options(USER_STATUSES);
  readonly languageOptions = [
    { label: 'English', value: 'en' }, { label: 'Hindi', value: 'hi' }, { label: 'Punjabi', value: 'pa' },
    { label: 'French', value: 'fr' }
  ];

  readonly users = signal<AppUser[]>([]);
  readonly roles = signal<Role[]>([]);
  readonly locations = signal<Location[]>([]);
  readonly loading = signal(true);

  readonly roleName = computed(() => {
    const map = new Map(this.roles().map((r) => [r.id, r.name]));
    return (id: string) => map.get(id) ?? 'Removed role';
  });
  readonly locationOptions = computed(() => this.locations().map((l) => ({ label: `${l.code} ${l.name}`, value: l.id })));

  readonly columns = ['name', 'email', 'roles', 'status', 'lastLogin', 'actions'];
  private readonly editor = viewChild.required<TemplateRef<unknown>>('editor');
  private editorRef: MatDialogRef<unknown> | null = null;

  readonly editing = signal<AppUser | null>(null);
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  readonly form = this.fb.nonNullable.group({
    fullName: ['', [Validators.required, Validators.maxLength(150)]],
    userType: ['STAFF', Validators.required],
    email: ['', Validators.email],
    mobile: ['', Validators.pattern(/^\+?[0-9]{7,15}$/)],
    status: ['INVITED'],
    preferredLanguage: ['en'],
    roleIds: [[] as string[]],
    locationIds: [[] as string[]]
  });

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    forkJoin({
      users: this.api.users(),
      roles: this.api.roles(),
      locations: this.session.can('location.view') ? this.api.locations() : of([] as Location[])
    }).subscribe({
      next: ({ users, roles, locations }) => {
        this.users.set(users);
        this.roles.set(roles);
        this.locations.set(locations);
        this.loading.set(false);
      },
      error: (error) => {
        this.loading.set(false);
        this.notify.error(`Could not load users. ${errorMessage(error)}`);
      }
    });
  }

  openNew(): void {
    this.editing.set(null);
    this.form.reset();
    this.formError.set(null);
    this.openEditor();
  }

  openEdit(user: AppUser): void {
    this.editing.set(user);
    this.formError.set(null);
    this.form.reset({
      fullName: user.fullName, userType: user.userType, email: user.email ?? '', mobile: user.mobile ?? '',
      status: user.status, preferredLanguage: user.preferredLanguage, roleIds: user.roleIds, locationIds: user.locationIds
    });
    this.openEditor();
  }

  private openEditor(): void {
    this.editorRef = this.dialog.open(this.editor(), { width: 'min(48rem, 95vw)', maxWidth: '95vw', autoFocus: 'first-tabbable' });
  }

  closeEditor(): void {
    this.editorRef?.close();
  }

  save(): void {
    const v = this.form.getRawValue();
    if (this.form.invalid || (!v.email && !v.mobile)) {
      this.form.markAllAsTouched();
      if (!v.email && !v.mobile) {
        this.formError.set('Enter an email or a mobile number. The email is used to sign in.');
      }
      return;
    }
    const existing = this.editing();
    this.saving.set(true);
    this.formError.set(null);
    this.api.saveUser(existing?.id ?? null, { ...v, email: v.email || null, mobile: v.mobile || null }).subscribe({
      next: (user) => {
        this.saving.set(false);
        this.closeEditor();
        this.notify.success(`${existing ? 'Updated' : 'Added'} ${user.fullName}`);
        this.reload();
      },
      error: (error) => {
        this.saving.set(false);
        this.formError.set(errorMessage(error));
      }
    });
  }

  askDelete(user: AppUser): void {
    DeleteDialog.ask(this.dialog, user.fullName, 'Deactivate',
      'They can no longer sign in. Their past work keeps their name.').subscribe((reason) => {
      if (!reason) {
        return;
      }
      this.api.softDelete('users', user.id, reason).subscribe({
        next: () => {
          this.notify.success(`${user.fullName} deactivated`);
          this.reload();
        },
        error: (error) => this.notify.error(`Could not deactivate. ${errorMessage(error)}`)
      });
    });
  }

  statusClass(status: string): string {
    return status === 'ACTIVE' ? 'ok' : status === 'INVITED' ? 'info' : status === 'SUSPENDED' ? 'warn' : 'danger';
  }
}
