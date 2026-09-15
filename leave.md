# Leave Management

This document explains how the `employee-management` microservice (Spring Boot, package
`com.hrms.employee.management`) implements Leave tracking: the data model, request flow, balance
mechanics, scheduled disbursal jobs, the approval hand-off to an external service, and the REST API
surface. It also calls out gaps in the current implementation that are worth knowing about before
extending it.

For the equivalent Work-From-Home subsystem, see [wfh.md](wfh.md).

---

## 1. Overview

Leave is modeled around three entities:

| Concern | Entity |
|---|---|
| Request record (one per applied date range) | `LeaveTracker` |
| Running balance per employee/leave-type/year | `EmployeeLeaveBalance` |
| Immutable credit/debit ledger | `LeaveTransaction` |

Key classes:

- `LeaveTrackerController` (`/employees/{employeeId}/leave-tracker`) — apply / read leave requests.
- `LeaveBalanceController` (`/employee/leave-balance`) — balances, initialization, deduction,
  manual disbursal triggers.
- `LeaveTrackerServiceImpl` — apply-leave business logic.
- `LeaveBalanceService` — balance initialization and deduction.
- `LeaveDisbursalSchedulerService` — cron-driven balance credit jobs.

Leave also notifies an external **Utility service** via `ActionItemService` so an employee's
manager gets a pending approval item — see [Section 6](#6-approval-workflow-action-items).

---

## 2. Domain Model

**`LeaveTracker`** (table auto-named `leave_tracker`) — one row per leave application:
- `id`, `employee` (`@ManyToOne` to `Employee`), `startDate`, `endDate`, `leaveType` (free-text
  string, e.g. `"Sick"`, `"Casual"`), `status` (free-text, set to `"Pending"` on creation),
  `reason`.

**`EmployeeLeaveBalance`** (table `employee_leave_balance`) — the employee's balance for one leave
type in one year:
- `employeeId`, `leaveTypeName`, `leaveBalance` (current available days), `carryForwardDays`,
  `remainingDays` (auto-derived), `year`, `isActive`, `createdAt`/`updatedAt`.
- `@PrePersist`/`@PreUpdate` hooks recompute `remainingDays = leaveBalance + carryForwardDays`
  automatically on every save.

**`LeaveTransaction`** (table `leave_transactions`) — append-only audit ledger:
- `employeeId`, `leaveTypeName`, `transactionType` (enum `LeaveTransactionType`: `CREDIT`, `DEBIT`,
  `CARRY_FORWARD`, `INITIALIZATION`, `ADJUSTMENT`), `days`, `createdAt` (auto-stamped).

---

## 3. Applying for Leave — `LeaveTrackerServiceImpl.applyLeave`

`POST /employees/{employeeId}/leave-tracker` with a `LeaveTrackerDto` body (`leaveType`,
`startDate`, `endDate`, `reason`, ...). Flow:

1. Look up the `Employee`; 404-equivalent `RuntimeException` if not found.
2. Load the employee's **active** leave balances (`findByEmployeeIdAndIsActiveTrue`) and find the
   entry matching the requested `leaveType`. If there's no active balance record for that leave
   type at all, or none matching the type, a `BusinessException` is thrown.
3. Compute requested days as `ChronoUnit.DAYS.between(startDate, endDate) + 1` and reject
   with `BusinessException` if it exceeds `leaveBalance.getLeaveBalance()`.
4. Persist a new `LeaveTracker` with `status = "Pending"`.
5. Call `ActionItemService.createActionItem(...)` to raise an approval task for the employee's
   manager (see [Section 6](#6-approval-workflow-action-items)).
6. Return a simple `LeaveTrackerResponse("Leave applied successfully", "Success")`.

**Important:** balance is only *validated* here, not *deducted*. The employee's `leaveBalance` is
untouched at apply time — deduction is a separate, explicitly-invoked step
(`LeaveBalanceController.deductLeaveFromEmployee`, see [Section 5](#5-leave-deduction)). Nothing in
this codebase calls that endpoint automatically when a leave is approved, so deduction currently
depends on an external caller (presumably the Utility/action-item service, once a manager approves)
invoking it.

### Reading leave data

- `GET /employees/{employeeId}/leave-tracker/{id}` — single leave record by id.
- `GET /employees/{employeeId}/leave-tracker` — full leave history for the employee
  (`findByEmployee_EmployeeId`).
- `GET /employees/{employeeId}/leave-tracker/reports/history?startDate=&endDate=` — leaves whose
  `startDate`/`endDate` fall fully inside the given range
  (`findByEmployee_EmployeeIdAndStartDateGreaterThanEqualAndEndDateLessThanEqual`).
- `GET /employee/leave-balance/{employeeId}` — all active balances for the *current year*
  (`Year.now()`), mapped to `LeaveBalanceDto`.

---

## 4. Leave Balance Initialization

Leave *types* themselves (name, default total days, carry-forward rules, disbursal frequency) are
not owned by this service — they live in an external **Company service**, fetched at
`${company.service.base.url}/leave-types`.

- `initializeLeaveBalanceForNewEmployee(employeeId)` — called from
  `POST /employee/leave-balance/initialize/{employeeId}`. Fetches all leave types from the Company
  service and creates one `EmployeeLeaveBalance` row per type for the current year, each logged as
  an `INITIALIZATION` transaction. Intended to run when a new employee joins.
- `initializeLeaveBalanceForNewLeaveType(leaveType)` — called from
  `POST /employee/leave-balance/initialize-for-new-leave-type`. Backfills a balance row for *every*
  existing employee when a brand-new leave type is introduced company-wide.

---

## 5. Leave Deduction

`POST /employee/leave-balance/{employeeId}/deduct/{leaveId}` →
`LeaveBalanceService.deductLeaveFromEmployee`:

1. Loads the `LeaveTracker` by id and the matching `EmployeeLeaveBalance` by
   `(employeeId, leaveType)`.
2. Recomputes days the same way as apply-time (`ChronoUnit.DAYS.between(startDate, endDate) + 1`).
3. Subtracts from `leaveBalance` (note: `remainingDays` is set directly here rather than relying on
   the entity's `@PreUpdate` recompute, so `carryForwardDays` is effectively dropped from
   `remainingDays` after a deduction — see [Known Gaps](#9-known-gaps--issues)).
4. Saves the balance and appends a `DEBIT` `LeaveTransaction`.

This is not wired to `applyLeave` — it's a standalone call, meant to be triggered once a leave
request is approved.

---

## 6. Scheduled Leave Disbursal — `LeaveDisbursalSchedulerService`

Periodically credits every employee's leave balance for each leave type, driven by the leave
type's `disbursalFrequency` as configured in the Company service. Each job pulls the relevant leave
types from the Company service (`${company.service.url}/leave-types/schedule/{frequency}`,
tenant-scoped via the `X-Tenant-Id` header from `TenantContext`), then calls the shared
`disburseLeave(leaveName, divisor, dto)`.

| Job | Cron | Endpoint | Divisor | Meaning |
|---|---|---|---|---|
| `disburseMonthlyLeave` | `0 0 0 1 * ?` (1st of every month) | `/leave-types/schedule/monthly` | 12 | 1/12 of annual allotment per month |
| `disburseQuarterlyLeave` | `0 0 0 1 1,4,7,10 ?` (Jan/Apr/Jul/Oct 1st) | `/leave-types/schedule/quarterly` | 4 | 1/4 of annual allotment per quarter |
| `disburseHalfYearlyLeave` | `0 0 0 1 1,7 ?` (Jan 1 & Jul 1) | `/leave-types/schedule/half_yearly` | 2 | 1/2 of annual allotment per half |
| `disburseYearlyLeave` | `0 0 0 1 1 ?` (Jan 1) | `/leave-types/schedule/yearly` | 1 | Full annual allotment |

`disburseLeave(leaveName, divisor, dto)`:
1. `daysToDisburse = round(dto.getTotalDays() / divisor, 2 decimal places)`.
2. Loads every employee and every existing balance row for that leave type, keyed by
   `(employeeId, leaveTypeName)` via `EmployeeLeaveKey`.
3. For employees with no existing balance row for that type, a new `EmployeeLeaveBalance` is
   created in-memory (year = current year, starting balance 0).
4. Adds `daysToDisburse` to each employee's balance and batch-saves all balances
   (`saveAll`) plus one `CREDIT` `LeaveTransaction` per employee (`saveAll`).

Each of these jobs is also manually triggerable via `LeaveBalanceController`
(`/disburse-monthly-leave`, `/disburse-yearly-leave`, `/disburse-quarterly-leave`,
`/disburse-half-yearly-leave`) — useful for ops/back-filling without waiting for the cron.

---

## 7. Approval Workflow (Action Items)

There is no in-service "approve" or "reject" endpoint for leave. Instead, `applyLeave` calls
`ActionItemService.createActionItem(employeeId, savedLeaveTracker, employee.getAssignedManagerId())`:

- If the employee has no `assignedManagerId`, the call is a no-op (silently returns — no approval
  item is raised, and the request stays in `Pending` indefinitely).
- Otherwise, `ActionItemHelper.convertToLeaveRequest` builds an `ActionItemExtRequest`
  (`initiatorUserId`, `assigneeUserId` = manager, `type` = `LEAVE`, `referenceId` = the leave
  tracker's id, title `"Leave Application Notification"`).
- This is POSTed to the external **Utility service** at `${utility_base_url}/action-item`, tagged
  with the current tenant via the `X-Tenant-Id` header.

Approval/rejection UI and the resulting status transitions (`Pending` → `Approved`/`Rejected`) and
any consequent balance deduction are therefore owned by that external Utility service, which is
expected to call back into `LeaveBalanceController`'s deduct endpoint (Section 5) — there is no
webhook/callback controller for this in the current codebase.

---

## 8. Multi-Tenancy & External Dependencies

All outbound calls to the Company and Utility services carry an `X-Tenant-Id` header populated from
`TenantContext` (a `ThreadLocal<String>`), expected to be set upstream by a filter/interceptor
before any leave service method that talks to an external service runs.

| Property | Default | Used for |
|---|---|---|
| `company.service.base.url` | `${COMPANY_BASE_URL:http://localhost:82323}` | Fetching leave types on new-employee/new-leave-type initialization (`LeaveBalanceService`) |
| `company.service.url` | `https://api.pp.hrms.work` | Fetching scheduled leave-type disbursal lists (`LeaveDisbursalSchedulerService`) |
| `utility_base_url` | `${UTILITY_BASE_URL:https://api.pp.hrms.work}` | Creating approval action items (`ActionItemService`) |

Note `company.service.base.url` and `company.service.url` are two separate properties pointing at
(likely) the same logical service but configured independently — see
[Known Gaps](#9-known-gaps--issues).

---

## 9. REST API Reference

| Method | Path | Purpose |
|---|---|---|
| POST | `/employees/{employeeId}/leave-tracker` | Apply for leave |
| GET | `/employees/{employeeId}/leave-tracker/{id}` | Get one leave record |
| GET | `/employees/{employeeId}/leave-tracker` | Full leave history |
| GET | `/employees/{employeeId}/leave-tracker/reports/history?startDate=&endDate=` | Leave records in a date range |
| GET | `/employee/leave-balance/{employeeId}` | Current-year active leave balances |
| POST | `/employee/leave-balance/initialize/{employeeId}` | Seed balances for a new employee |
| POST | `/employee/leave-balance/initialize-for-new-leave-type` | Seed a new leave type across all employees |
| POST | `/employee/leave-balance/{employeeId}/deduct/{leaveId}` | Deduct balance for an (approved) leave |
| POST | `/employee/leave-balance/disburse-monthly-leave` | Manually trigger monthly credit run |
| POST | `/employee/leave-balance/disburse-yearly-leave` | Manually trigger yearly credit run |
| POST | `/employee/leave-balance/disburse-quarterly-leave` | Manually trigger quarterly credit run |
| POST | `/employee/leave-balance/disburse-half-yearly-leave` | Manually trigger half-yearly credit run |

---

## 10. Known Gaps / Issues

These are worth knowing before building on top of this module:

- **Deduction and approval are disconnected.** `applyLeave` only validates/records; nothing in
  this service transitions `status` away from `Pending` or calls the deduct endpoint
  automatically. That logic must live entirely in the external Utility service today.
- **`deductLeaveFromEmployee` bypasses the balance's own recompute.** It sets `remainingDays`
  directly to the post-deduction `leaveBalance`, discarding `carryForwardDays` from
  `remainingDays` even though the entity's `@PreUpdate` hook would normally re-derive
  `remainingDays = leaveBalance + carryForwardDays`.
- **`status`/`leaveType` are free-text strings**, not enums or foreign keys to a managed list —
  there's no server-side validation preventing arbitrary values.
- **Two separate config properties for the same external service:** `company.service.base.url`
  (used by `LeaveBalanceService` for initialization) and `company.service.url` (used by the
  scheduler service) both default to what looks like the same Company service, configured
  independently in `application.properties`.
- **Large amounts of commented-out code** remain in `LeaveBalanceService` and
  `LeaveBalanceController` (single-leave-type balance lookup, direct leave assignment, bulk
  assignment, leave-type deactivation) — these features are stubbed but not currently wired up or
  exposed via any active endpoint.
