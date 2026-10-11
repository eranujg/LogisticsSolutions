import { Component, computed, inject, OnInit, signal, TemplateRef, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { forkJoin } from 'rxjs';
import { ApiService, errorMessage } from '../../core/api/api.service';
import { Permission, Role } from '../../core/api/models';
import { SessionService } from '../../core/session/session.service';
import { humanize } from '../../core/ui/labels';
import { NotifyService } from '../../core/ui/notify.service';

const ACTIONS = ['view', 'create', 'edit', 'delete', 'cancel', 'approve', 'print', 'export'] as const;

/**
 * Roles and what each may do. Built-in roles are read-only templates;
 * copy one to make a role you can change.
 */
@Component({
  selector: 'tms-roles-page',
  imports: [
    FormsModule, MatButtonModule, MatCheckboxModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatIconModule
  ],
  templateUrl: './roles-page.html',
  styleUrl: './roles-page.scss'
})
export class RolesPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly notify = inject(NotifyService);
  private readonly dialog = inject(MatDialog);
  readonly session = inject(SessionService);

  readonly actions = ACTIONS;
  readonly humanize = humanize;
  readonly roles = signal<Role[]>([]);
  readonly catalogue = signal<Permission[]>([]);
  readonly selectedId = signal<string | null>(null);
  readonly granted = signal<Set<string>>(new Set());
  readonly dirty = signal(false);
  readonly saving = signal(false);

  private readonly copier = viewChild.required<TemplateRef<unknown>>('copier');
  private copyRef: MatDialogRef<unknown> | null = null;
  readonly copyCode = signal('');
  readonly copyName = signal('');

  readonly selected = computed(() => this.roles().find((r) => r.id === this.selectedId()) ?? null);
  readonly editable = computed(() => !!this.selected() && !this.selected()!.system && this.session.can('role.edit'));
  readonly modules = computed(() => {
    const seen = new Map<string, boolean>();
    for (const p of this.catalogue()) {
      seen.set(p.module, p.sensitive);
    }
    return [...seen.entries()].map(([module, sensitive]) => ({ module, sensitive }));
  });

  ngOnInit(): void {
    this.reload();
  }

  reload(selectId?: string): void {
    forkJoin({ roles: this.api.roles(), catalogue: this.api.permissions() }).subscribe({
      next: ({ roles, catalogue }) => {
        this.roles.set(roles);
        this.catalogue.set(catalogue);
        this.select(selectId ?? this.selectedId() ?? roles[0]?.id ?? null);
      },
      error: (error) => this.notify.error(`Could not load roles. ${errorMessage(error)}`)
    });
  }

  select(id: string | null): void {
    this.selectedId.set(id);
    const role = this.roles().find((r) => r.id === id);
    this.granted.set(new Set(role?.permissions.map((p) => p.permissionCode) ?? []));
    this.dirty.set(false);
  }

  has(module: string, action: string): boolean {
    return this.granted().has(`${module}.${action}`);
  }

  toggle(module: string, action: string, on: boolean): void {
    const next = new Set(this.granted());
    const code = `${module}.${action}`;
    if (on) {
      next.add(code);
    } else {
      next.delete(code);
    }
    this.granted.set(next);
    this.dirty.set(true);
  }

  openCopy(): void {
    const role = this.selected();
    this.copyCode.set(role ? `${role.code}_2` : '');
    this.copyName.set(role ? `${role.name} (custom)` : '');
    this.copyRef = this.dialog.open(this.copier(), { width: '26rem', maxWidth: '95vw' });
  }

  closeCopy(): void {
    this.copyRef?.close();
  }

  copy(): void {
    const role = this.selected();
    if (!role) {
      return;
    }
    this.api.copyRole(role.id, this.copyCode().trim().toUpperCase(), this.copyName().trim()).subscribe({
      next: (copy) => {
        this.copyRef?.close();
        this.notify.success(`Copied. ${copy.name} can now be changed.`);
        this.reload(copy.id);
      },
      error: (error) => this.notify.error(`Could not copy role. ${errorMessage(error)}`)
    });
  }

  save(): void {
    const role = this.selected();
    if (!role) {
      return;
    }
    const scopes = new Map(role.permissions.map((p) => [p.permissionCode, p.scope]));
    const body = {
      code: role.code,
      name: role.name,
      description: role.description,
      permissions: [...this.granted()].map((code) => ({ permissionCode: code, scope: scopes.get(code) ?? 'COMPANY' }))
    };
    this.saving.set(true);
    this.api.saveRole(role.id, body).subscribe({
      next: () => {
        this.saving.set(false);
        this.notify.success('Role saved. Users get the change at their next sign-in.');
        this.reload(role.id);
      },
      error: (error) => {
        this.saving.set(false);
        this.notify.error(`Could not save role. ${errorMessage(error)}`);
      }
    });
  }
}
