# `employee_leave_balance` — How It Works

Entity: `com.hrms.employee.management.dao.EmployeeLeaveBalance`

## Fields

| Field | Meaning |
|---|---|
| `id` | Auto-increment PK |
| `employeeId` | Which employee this row belongs to |
| `leaveTypeName` | Which leave type (e.g. "Casual Leave", "Sick Leave") — note there's a commented-out `leaveTypeId`, so it's currently matched by **name**, not a foreign key |
| `leaveBalance` | The leave days credited to the employee for this type/year (accrued/allocated) |
| `carryForwardDays` | Days carried over from a previous year |
| `remainingDays` | **Derived**, not independently set — `@PrePersist`/`@PreUpdate` auto-recompute it as `leaveBalance + carryForwardDays` every time the row is saved (see `calculateRemainingDays()`) |
| `year` | Which calendar year this balance row is for (one row per employee per leave type per year) |
| `isActive` | Soft-delete/deactivation flag |
| `createdAt` / `updatedAt` | Audit timestamps, auto-set |

## Lifecycle

1. **Creation** — `LeaveBalanceService.initializeLeaveBalanceForNewEmployee()` fetches leave types from an external company service, uses `ProrataLeaveCalculator` to prorate the entitlement based on join date/cycle/frequency, then calls `createLeaveBalance(...)` to persist a row.
2. **Disbursal** — `LeaveDisbursalSchedulerService` periodically adds days (monthly/quarterly/half-yearly/yearly) via `addDays()`, which bumps `leaveBalance` and recalculates `remainingDays`.
3. **Deduction** — `deductLeaveFromEmployee()` looks up the balance by `employeeId` + `leaveTypeName`, subtracts the day count from an approved `LeaveTracker` entry, and logs a `LeaveTransaction`.

## Known bugs (as of this writing)

- `createLeaveBalance(...)` in `LeaveBalanceService.java:154-169` takes a `leavesCount` parameter (the prorated result) but never calls `balance.setLeaveBalance(leavesCount)`. Every newly-initialized balance is persisted with `leaveBalance = 0.0` (the field's default), so `remainingDays` also computes to `0`, even though a real prorated value was calculated upstream.
- `initializeLeaveBalanceForNewLeaveType()` (`LeaveBalanceService.java:90-97`) passes `leavesCount` into `createLeaveBalance(...)`, but `leavesCount` is never declared in that method — this won't compile as-is.
