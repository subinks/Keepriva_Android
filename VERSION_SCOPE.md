# Keepriva v17 Mainline Scope

Base: Private Vault v16 / 2.0.0-alpha10 security-hardened implementation.

New mainline version:

- Working app name changed to **Keepriva**.
- Version: **2.1.0-alpha1**.
- versionCode: **16**.
- Adds nested categories/sub-categories with preference-driven depth (default 3, maximum 5).
- Adds move/re-parent/delete behavior for nested categories.
- Preserves hierarchy in encrypted `.pvault` backup/restore.
- Adds optional `parentCategory` to JSON import template.
- Existing v16 custom categories remain compatible and become top-level categories.

Not included in this mainline ZIP:

- Secure Inter-App API (Approach 1)
- Manual Single-Value Share (Approach 2)
- Android Autofill (Approach 3)

Those remain separate variants and can later be rebased onto this v17 mainline.

Production identity checks for the name **Keepriva** and final Android package ID remain pending.
