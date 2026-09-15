# Work-From-Home (WFH) Management

This document explains how the `employee-management` microservice (Spring Boot, package
`com.hrms.employee.management`) implements WFH tracking: the data model, request flow, balance
mechanics, scheduled disbursal jobs, the approval hand-off to an external service, and the REST API
surface. It also calls out gaps in the current implementation that are worth knowing about before
extending it.

For the equivalent Leave subsystem, see [leave.md](leave.md).

---

## 1. Overview

WFH is modeled around three entities, structurally parallel to Leave:

| Concern | Entity |
|---|---|
| Request record (one per applied date range) | `WFHTracker` |
| Running balance per employee/WFH-type | `EmployeeWfhBalance` |
| Immutable credit/debit ledger | `WFHTransaction` |

Key classes:

- `WFHTrackerController` (`/employees/{employeeId}/wfh`) — apply / read WFH requests.
- `WFHBalanceController` (`/employee/wfh-balance`) — manual balance deduct/disburse.
- `WFHSeriveImpl` — apply-WFH business logic (note the class name typo in the codebase).
- `WfhBalanceServiceImpl` — balance deduction and crediting.
- `WFHDisbursalSchedulerService` — cron-driven balance credit jobs.

WFH also notifies an external **Utility service** via `ActionItemService` so an employee's manager
gets a pending approval item — see [Section 5](#5-approval-workflow-action-items).

---

## 2. Domain Model

**`WFHTracker`** (table `wfh_tracker`) — one row per WFH application:
- `id`, `employee` (`@ManyToOne` to `Employee`), `startDate`, `endDate`, `wfhCreditOption`,
  `status` (set to `"PENDING"` on creation), `reason`, `createdDate`/`updatedDate` (auto-stamped
  via `@PrePersist`/`@PreUpdate`).

**`EmployeeWfhBalance`** — the employee's WFH day balance per WFH type:
- `wfhTypeName` (e.g. `quarterly`, `monthly`, `yearly`, `half_yearly` — this field actually stores
  the *disbursal frequency* used as a type label), `employeeId`, `wfhBalance` (`Integer`).

**`WFHTransaction`** (table `wfh_transactions`) — audit ledger analogous to `LeaveTransaction`:
- `employeeId`, `wfhTypeName`, `days`, `transactionType` (plain `String`, e.g. `"CREDIT"` — unlike
  leave, this is not an enum), `createdAt`.

---

## 3. Applying for WFH — `WFHSeriveImpl.applyWFH`

`POST /employees/{employeeId}/wfh` with a `WFHTrackerRequest` body (`startDate`, `endDate`,
`wfhCreditOption`, `reason`). Flow:

1. Look up the `Employee` (404-equivalent `RuntimeException` if missing).
2. Build a `WFHTracker` with `status = "PENDING"` and persist it.
3. Call `ActionItemService.createActionItem(...)` to notify the employee's manager.
4. Return the saved `WFHTracker`.

Unlike leave, **no balance check happens at apply time** — a WFH request can be submitted
regardless of remaining WFH balance; balance is only touched later via the explicit deduct
endpoint ([Section 4](#4-wfh-balance-deduct--disburse)).

### Reading WFH data

- `GET /employees/{employeeId}/wfh/{id}` — single record.
- `GET /employees/{employeeId}/wfh` — full history (`findAllByEmployee_EmployeeId`).
- `GET /employees/{employeeId}/wfh/date?date=` — the WFH record (if any) covering a specific date
  (`startDate <= date <= endDate`).
- `GET /employees/{employeeId}/wfh/reports/history?startDate=&endDate=` — WFH records within a
  window, returned as `WFHTrackerResponse` (id, dates, reason, status).

---

## 4. WFH Balance Deduct / Disburse — `WfhBalanceServiceImpl`

Exposed via `WFHBalanceController` under `/employee/wfh-balance`:

- `POST /employee/wfh-balance/{employeeId}/deduct/{wfhTrackerId}` — loads the employee's
  `EmployeeWfhBalance`, computes days from the referenced `WFHTracker`
  (`ChronoUnit.DAYS.between(startDate, endDate) + 1`), throws if the balance is insufficient,
  otherwise subtracts and saves.
- `POST /employee/wfh-balance/{employeeId}/disburse/{wfhTrackerId}` — same day calculation, but
  *adds* days back to the balance (used for reversing/crediting, e.g. if a WFH request is
  rejected/cancelled after being provisionally deducted).

Neither endpoint writes a `WFHTransaction` — transaction logging for manual deduct/disburse via
this controller does not currently happen (only the scheduled disbursal job logs transactions; see
[Known Gaps](#7-known-gaps--issues)).

There is no automatic wiring between `applyWFH` and this deduction step — like leave, it's a
standalone call meant to be triggered once a WFH request is approved.

---

## 5. Scheduled WFH Disbursal — `WFHDisbursalSchedulerService`

Structurally identical to the leave scheduler, pulling WFH *types* from the external **Company
service** (`${company.service.url}/wfh-types/schedule/{frequency}`), tenant-scoped via the
`X-Tenant-Id` header from `TenantContext`.

| Job | Cron | Endpoint | Divisor |
|---|---|---|---|
| `disburseMonthlyWFH` | `0 0 0 1 * ?` (1st of every month) | `/wfh-types/schedule/monthly` | 12 |
| `disburseQuarterlyWFH` | `0 0 0 1 1,4,7,10 ?` (Jan/Apr/Jul/Oct 1st) | `/wfh-types/schedule/quarterly` | 4 |
| `disburseHalfYearlyWFH` | `0 0 0 1 1,7 ?` (Jan 1 & Jul 1) | `/wfh-types/schedule/half_yearly` | 2 |
| `disburseYearlyWFH` | `0 0 0 1 1 ?` (Jan 1) | `/wfh-types/schedule/yearly` | 1 |

`disburseWFH(divisor, dto)`:
1. For **every** employee, builds a brand-new `EmployeeWfhBalance` set to
   `round(dto.getTotalDays() / divisor)` and batch-saves all of them.
2. Logs one `CREDIT` `WFHTransaction` per employee.

Note this always **creates a new balance row** rather than adding to an existing one for that WFH
type (contrast with the leave scheduler, which looks up and increments an existing row) — see
[Known Gaps](#7-known-gaps--issues). Unlike the leave scheduler, these WFH jobs have no equivalent
manual-trigger endpoints in `WFHBalanceController`.

---

## 6. Approval Workflow (Action Items)

There is no in-service "approve" or "reject" endpoint for WFH. Instead, `applyWFH` calls
`ActionItemService.createActionItem(employeeId, wfhTracker, employee.getAssignedManagerId())`:

- If the employee has no `assignedManagerId`, the call is a no-op (silently returns — no approval
  item is raised, and the request stays in `PENDING` indefinitely).
- Otherwise, `ActionItemHelper.convertToWFHRequest` builds an `ActionItemExtRequest`
  (`initiatorUserId`, `assigneeUserId` = manager, `type` = `WFH`, `referenceId` = the WFH
  tracker's id, title `"Work From Home Notification"`).
- This is POSTed to the external **Utility service** at `${utility_base_url}/action-item`, tagged
  with the current tenant via the `X-Tenant-Id` header.

Approval/rejection UI and the resulting status transitions (`PENDING` → `Approved`/`Rejected`) and
any consequent balance deduction are therefore owned by that external Utility service, which is
expected to call back into `WFHBalanceController`'s deduct endpoint (Section 4) — there is no
webhook/callback controller for this in the current codebase.

### Multi-Tenancy & External Dependencies

| Property | Default | Used for |
|---|---|---|
| `company.service.url` | `https://api.pp.hrms.work` | Fetching scheduled WFH-type disbursal lists (`WFHDisbursalSchedulerService`) |
| `utility_base_url` | `${UTILITY_BASE_URL:https://api.pp.hrms.work}` | Creating approval action items (`ActionItemService`) |

---

## 7. REST API Reference

| Method | Path | Purpose |
|---|---|---|
| POST | `/employees/{employeeId}/wfh` | Apply for WFH |
| GET | `/employees/{employeeId}/wfh/{id}` | Get one WFH record |
| GET | `/employees/{employeeId}/wfh` | Full WFH history |
| GET | `/employees/{employeeId}/wfh/date?date=` | WFH record covering a given date |
| GET | `/employees/{employeeId}/wfh/reports/history?startDate=&endDate=` | WFH records in a date range |
| POST | `/employee/wfh-balance/{employeeId}/deduct/{wfhTrackerId}` | Deduct WFH balance for a request |
| POST | `/employee/wfh-balance/{employeeId}/disburse/{wfhTrackerId}` | Credit back WFH balance for a request |

---

## 8. Known Gaps / Issues

These are worth knowing before building on top of this module:

- **Deduction and approval are disconnected.** `applyWFH` only creates the request; nothing in
  this service transitions `status` away from `PENDING` or calls the deduct endpoint
  automatically. That logic must live entirely in the external Utility service today.
- **No balance check at apply time.** Unlike leave, `applyWFH` doesn't verify the employee has
  enough WFH balance before creating the request — insufficient balance is only caught later, at
  deduct time.
- **`WfhBalanceServiceImpl` looks up the wrong repository/key.** It calls
  `employeeWfhRepository.findById(employeeId)` where `employeeWfhRepository` is
  `EmployeeWfhRepository` (a generic `JpaRepository<EmployeeWfhBalance, Long>`), so `employeeId`
  is being used as the auto-generated primary key `id`, not the employee's actual ID — and an
  employee can have multiple `EmployeeWfhBalance` rows (one per WFH type), so this doesn't
  disambiguate by type either. `EmployeeWfhBalanceRepository.findByEmployeeIdAndWfhTypeName(...)`
  looks like the intended query but isn't used here.
- **`WFHBalanceController` endpoints are unlikely to bind correctly.** `deductWfhBalance` and
  `disburseWfhBalance` take `Long employeeId, Long wfhTrackerId` without `@PathVariable`
  annotations even though the path template declares `{employeeId}` and `{wfhTrackerId}` —
  Spring MVC needs explicit `@PathVariable` (or a matching parameter name plus `-parameters`
  compiler flag) for this to resolve; also neither method returns a `ResponseEntity`.
- **Scheduled WFH disbursal always inserts new rows instead of crediting existing balances.**
  `WFHDisbursalSchedulerService.disburseWFH` builds a fresh `EmployeeWfhBalance` per employee per
  run and saves it, rather than finding-and-incrementing the existing balance for that
  `(employeeId, wfhTypeName)` the way `LeaveDisbursalSchedulerService.disburseLeave` does (see
  [leave.md](leave.md)). Over multiple disbursal cycles this produces duplicate balance rows per
  employee/type instead of one running balance.
- **Manual WFH deduct/disburse don't write `WFHTransaction` rows.** Only the scheduled disbursal
  job logs to `WFHTransactionRepository`; the audit trail for manual deduct/disburse calls is
  incomplete. `WFHTransaction.transactionType` is also a raw `String` rather than an enum (compare
  `LeaveTransactionType`), so there's no compile-time guard against typos like `"Credit"` vs
  `"CREDIT"`.
- **`status`/`wfhCreditOption` are free-text strings**, not enums or foreign keys to a managed
  list — there's no server-side validation preventing arbitrary values.
- **No manual-trigger endpoints for the WFH disbursal jobs**, unlike leave which exposes
  `/employee/leave-balance/disburse-*` endpoints on `LeaveBalanceController` for ops/back-filling.
- **`WFHSeriveImpl` class name typo** (`Serive` instead of `Service`) — purely cosmetic but worth
  knowing when searching the codebase.
