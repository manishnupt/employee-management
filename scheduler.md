# Disbursal Scheduler — Audit, Flow Breaks & Pending Work

Scope: leave and WFH balance disbursal in `employee-management`. This covers the cron jobs,
prorata on joining, initialization, and the deduct paths that use the balances the scheduler writes.
Branch at time of audit: `feature/proratacalc` (HEAD `19fe220`).

Related docs: [leave.md](leave.md), [wfh.md](wfh.md), [employee-leave-balance.md](employee-leave-balance.md).

---

## TL;DR — Is it foolproof?

**No.** The scheduler works only in a narrow case: one pod, one tenant (the default `tomato`), the
Company service up at midnight, no duplicate balance rows, and the calendar year 2026. Outside that
case it fails in several ways:

| # | Problem | Impact |
|---|---|---|
| 1 | Cron jobs run with **no tenant context** | Only the default tenant gets disbursals. Other tenants get nothing. |
| 2 | Prorata on joining **credits the rest of the year**, and the scheduler then **credits the same periods again** | New joiners on monthly, quarterly or half-yearly types get roughly double their entitlement. |
| 3 | **No idempotency or distributed lock** | 2 replicas, a retry or a repeated manual POST each credit again. |
| 4 | **Hard-coded 2026 cycle** in prorata calls | From 1 Jan 2027, every new joiner is prorated to **0**. |
| 5 | WFH scheduler **inserts a new row every run** and uses **integer division** | Balance rows pile up, WFH deduction then throws, and small allotments round to 0. |
| 6 | Leave approval logic is **inverted** | Rejected leaves deduct balance and approved leaves do not. |
| 7 | **No year-end rollover or carry-forward** | Balances never reset, and the `year` column goes stale, so the current-year GET returns nothing in 2027. |
| 8 | Manual disburse endpoints are **unauthenticated** | Anyone who can reach the service can inflate every balance. |

Items 1–6 are correctness bugs that will produce wrong balances in production. Fix them before
relying on the scheduler.

---

## 1. Current Flow (as implemented)

```
                       ┌──────────────── Company service ────────────────┐
                       │ /leave-types, /wfh-types           (init)       │
                       │ /leave-types/schedule/{freq}       (cron)       │
                       │ /wfh-types/schedule/{freq}         (cron)       │
                       └─────────────────────────────────────────────────┘
                                   ▲                         ▲
  createEmployee ──► initialize*ForNewEmployee          @Scheduled cron (00:00, JVM TZ)
                     (prorata, joinDate = now(),        monthly / quarterly / half_yearly / yearly
                      cycle = 2026-01-01..2026-12-31)         │
                                   │                         ▼
                                   ▼               Leave: findAll employees + findAll balances
                         employee_leave_balance            → add totalDays/divisor → saveAll
                         employee_wfh_balance      WFH:   new row per employee   → saveAll
                         *_transactions (ledger)
                                   │
  PUT /{id}/status ──► updateLeaveStatus / updateWFHStatus ──► deduct*
```

| Job | Cron | Divisor | Leave method | WFH method |
|---|---|---|---|---|
| Monthly | `0 0 0 1 * ?` | 12 | `disburseMonthlyLeave` | `disburseMonthlyWFH` |
| Quarterly | `0 0 0 1 1,4,7,10 ?` | 4 | `disburseQuarterlyLeave` | `disburseQuarterlyWFH` |
| Half-yearly | `0 0 0 1 1,7 ?` | 2 | `disburseHalfYearlyLeave` | `disburseHalfYearlyWFH` |
| Yearly | `0 0 0 1 1 ?` | 1 | `disburseYearlyLeave` | `disburseYearlyWFH` |

---

## 2. Flow Breaks — Scheduler (Critical)

### 2.1 Cron threads have no tenant → only the default tenant is processed — ✅ FIXED
> Each cron job now loops over every tenant returned by the tenant microservice
> (`${tenant.config.api.url}` → `api/v1/tenants/databases`, via `TenantRegistry`). The list is fetched
> on every run, so newly onboarded tenants are included. For each
> tenant, `TenantJobRunner` sets `TenantContext` and clears it afterwards. A failure in one tenant
> is logged and the loop moves on to the next tenant.

- `LeaveDisbursalSchedulerService.java:56-59`, `WFHDisbursalSchedulerService.java:71-72`
- `TenantContext` is a `ThreadLocal` that only `TenantFilter` sets, and only on HTTP request threads.
  The `@Scheduled` thread never sets it, so `getCurrentTenant()` returns `null`.
- `MultitenantDataSource` falls back to `defaultTenant` (`tomato`) when the key is `null`. The call
  to the Company service also sends `X-Tenant-Id: null`.
- **Result:** every cron run disburses only to `tomato`, using whatever leave types the Company
  service returns for a null tenant. Other tenants are never credited by cron.
- **Fix:** iterate over all tenants inside each job: `TenantContext.setCurrentTenant(t)` →
  run → clear in `finally`. Expose the tenant list from `MultiTenantConfiguration`, which already
  fetches it.

### 2.2 Prorata on joining + scheduler = double credit
- `LeaveBalanceService.java:106`, `WfhBalanceServiceImpl.java:160`, `ProrataLeaveCalculator.java:62-83`
- `calculateProrataLeaves` returns the joiner's entitlement for the **rest of the cycle**: the
  prorated join period plus **every future full period**. The monthly, quarterly and half-yearly
  crons then credit those future periods again.
- Example: 24/yr, MONTHLY, joins 16 Aug 2026.
  - Init credits ≈ 1.03 (Aug) + 4 × 2 (Sep–Dec) → **9.0**.
  - Cron credits 2 on each of 1 Sep, 1 Oct, 1 Nov and 1 Dec → **+8**.
  - Total **17**. The correct total is ~9.
- The YEARLY frequency is unaffected because it has no further run until 1 Jan.
- **Fix:** at join time, credit **only the prorated share of the current period**. Let the scheduler
  credit future periods. For YEARLY, the current period is the whole year, so the result is the same.

### 2.3 No idempotency, no distributed lock — ✅ FIXED
> Each `(tenant, kind, type, period)` is claimed in `disbursal_run` with
> `INSERT … ON CONFLICT DO NOTHING`, in the **same transaction** as the crediting
> (`TransactionTemplate`). That transaction covers one leave or WFH type.
>
> - A second replica or a repeat manual call blocks on the uncommitted claim, then gets 0 rows and skips.
> - A failed run rolls back its claim, so it can be retried.
>
> This was done without ShedLock. Side effects: 2.4 (missing transaction, one type aborting the
> rest) and the null-body NPE from 2.9 are fixed too. `disbursal_run` is created in each tenant
> database **manually**. Run this in every tenant database before deploying:
>
> ```sql
> CREATE TABLE IF NOT EXISTS disbursal_run (
>     id                BIGSERIAL PRIMARY KEY,
>     tenant_id         VARCHAR(100)     NOT NULL,
>     kind              VARCHAR(20)      NOT NULL,   -- LEAVE / WFH
>     type_name         VARCHAR(255)     NOT NULL,
>     frequency         VARCHAR(20)      NOT NULL,
>     period_key        VARCHAR(20)      NOT NULL,   -- 2026-10, 2026-Q4, 2026-H2, 2026
>     days_per_employee DOUBLE PRECISION NOT NULL,
>     created_at        TIMESTAMP        NOT NULL DEFAULT now(),
>     CONSTRAINT uk_disbursal_run UNIQUE (tenant_id, kind, type_name, period_key)
> );
> ```
>
> The `uk_disbursal_run` constraint is required: the claim's `ON CONFLICT` targets those exact
> columns. Without it the insert errors and nothing is disbursed.

- Every invocation unconditionally adds `totalDays / divisor`. Nothing records that a given
  `(tenant, type, period)` was already disbursed.
- The chart (`charts/emp-mgmt/values/prod.yaml`) sets `replicas: 2`. Each pod runs the cron, so
  everyone gets **2×** per run. No ShedLock or Quartz-JDBC dependency is in `pom.xml`.
- The manual endpoints (`/employee/leave-balance/disburse-*`) are additive too. Calling one twice,
  or retrying after a timeout, credits twice.
- **Fix:**
  - Add a `disbursal_run` table with a unique key `(tenant, kind[LEAVE/WFH], type_name, period_key)`,
    e.g. `2026-10`, `2026-Q4`, `2026-H2`, `2026`.
  - Insert first. If the unique key is violated, skip.
  - Add ShedLock (or equivalent) so only one pod runs each job.

### 2.4 `disburseLeave` is not transactional (self-invocation)
- `LeaveDisbursalSchedulerService.java:80,183`, `WFHDisbursalSchedulerService.java:84,87`
- `@Transactional` on `disburseLeave` / `disburseWFH` is bypassed because the caller is `this`, so
  the Spring proxy is never involved. Each `saveAll` commits on its own.
- A failure after `leaveBalanceRepository.saveAll` leaves balances credited with **no ledger rows**.
- One failing leave type aborts the loop, and the remaining types for that run are silently skipped.
- **Fix:** move per-type disbursal into a separate bean (or use `TransactionTemplate`). Wrap each type
  in try/catch so one failure does not block the rest, and record per-type status in `disbursal_run`.

### 2.5 Duplicate balance rows crash the whole leave run
- `LeaveDisbursalSchedulerService.java:196-204`
- `Collectors.toMap` throws `IllegalStateException: Duplicate key` if any employee has two rows for
  the same leave type.
- Duplicate rows are easy to create:
  - `/initialize/{employeeId}` is not idempotent.
  - `/initialize-for-new-leave-type` adds rows for everyone, including people who already have them.
  - A year rollover (see 2.7) would also add rows.
- One bad row means **no employee** gets that leave type this period.
- It also loads **all** balance rows of **all** years and types into memory. This does not scale.
- **Fix:** add a DB unique constraint `(employee_id, leave_type_name, year)`. Query
  `findByLeaveTypeNameAndYearAndIsActiveTrue`, and make init an upsert.

### 2.6 Hard-coded 2026 cycle
- `LeaveBalanceService.java:98-99,125-126`, `WfhBalanceServiceImpl.java:153-154,179-180`
- `cycleStart = 2026-01-01`, `cycleEnd = 2026-12-31`. From 1 Jan 2027, `joinDate.isAfter(cycleEnd)`
  makes the calculator return **0.0** for every new joiner.
- **Fix:** derive the cycle from `Year.now()`, or from a tenant fiscal-year setting supplied by the
  Company service.

### 2.7 No year-end rollover / carry-forward / lapse
- `LeaveDisbursalSchedulerService.java:200-222`
- The leave scheduler matches rows by `(employeeId, leaveName)` **ignoring year**. On 1 Jan 2027 it
  keeps adding to the 2026 row, and that row keeps `year = 2026`.
- `getEmployeeLeaveBalances` filters by `Year.now()`, so in 2027 employees see **empty balances**
  even though credit was added.
- `carryForward` (on `LeaveDisbursalDto` / `LeaveType`) and `carryForwardDays` are never used.
  Balances never lapse or reset.
- **Fix:** add a year-end job that runs **before** the 1 Jan disbursals. It should:
  1. Close the old-year rows.
  2. Create new-year rows with `carryForwardDays = carryForward ? min(remaining, cap) : 0`.
  3. Write `CARRY_FORWARD` ledger entries.

  After that, the scheduler should touch only current-year rows.

### 2.8 Soft-deleted / inactive employees still accrue
- `employeeRepository.findAll()` at `LeaveDisbursalSchedulerService.java:188` and
  `WFHDisbursalSchedulerService.java:89`
- Employees with `deleted = true` (and any `jobStatus` such as exited or notice) are credited every run.
- **Fix:** filter with `deleted = false`, an active `jobStatus`, and a joining date ≤ period start.
  Joiners in the current period are already prorated.

### 2.9 Fragile external call handling
- `getBody().length` → NPE when the body is null (`:71,103,135,167`). The WFH scheduler has no
  empty/null check at all (`:82`).
- There is no timeout on `RestTemplate` (`RestTemplateConfig` uses default builder settings), so a
  hung Company service can block the single scheduler thread.
- There is no retry, and a missed run (pod down at 00:00, Company service down) is **never caught
  up**. Spring cron does not backfill.
- The log message is wrong: monthly logs "No **quarterly** leave types found" (`:72`).
- **Fix:** add null-safe handling, connect/read timeouts, retry with backoff, and a startup or hourly
  "catch-up" check driven by `disbursal_run`, e.g. "is `2026-10` done for tenant X?".

### 2.10 Config: prod points to pre-prod, init URL is invalid
- `company.service.url=https://api.pp.hrms.work` is set only in `application.properties` and is not
  overridden in `application-prod.properties`, so **prod cron reads leave types from pre-prod**.
- `company.service.base.url` defaults to `http://localhost:82323`, which is **not a valid port**
  (it is > 65535). Unless `COMPANY_BASE_URL` is set, every init call fails.
  `EmployeeServiceImpl.createEmployee:99-111` swallows that error, so the new joiner silently has
  **no balances**.
- Init and cron use two different properties for the same service.
- **Fix:** use one property backed by an env var, with no hard-coded host. Fail fast on startup if
  it is missing.

### 2.11 Timezone
- Cron and `Year.now()` / `LocalDate.now()` use the JVM default zone. In a container that is
  usually UTC, so jobs fire at 05:30 IST. Prorata and "which year" checks near midnight on
  31 Dec / 1 Jan can land on the wrong side.
- **Fix:** `@Scheduled(cron = ..., zone = "Asia/Kolkata")`, ideally per-tenant, and pass a `Clock`
  everywhere.

---

## 3. Flow Breaks — WFH Scheduler Specific

| Issue | Location | Effect |
|---|---|---|
| Always `new EmployeeWfhBalance()`, never increments the existing row | `WFHDisbursalSchedulerService.java:91-97` | A new row per employee per run. The balance is **overwritten in practice** rather than accumulated, and rows grow without bound. |
| `year` never set on the scheduler rows | same | Rows have `year = 0`. |
| Integer division: `(int) Math.round(wfh.getTotalDays() / divisor)` | `:95,102,109` | `int / int` truncates **before** rounding. For 10/yr monthly → 0 every month. For 18/yr quarterly → 4 per quarter (16/yr). |
| `WFHDisbursalDto.wfhType` vs `WfhType.name` | `dto/WFHDisbursalDto.java` | If the schedule endpoint returns `name` like `/wfh-types` does, `wfhTypeName` is **null**. Verify against the Company service contract. |
| No manual trigger endpoints for WFH | `WFHBalanceController` | Ops cannot backfill a missed run. |
| No null/empty body check | `:82` | NPE when the Company service returns nothing. |

**Knock-on:** `deductWfhBalance` uses `employeeWfhRepository.findByEmployeeId(employeeId)`, which
returns a **single** entity (`EmployeeWfhRepository.java:14`). That fails in two cases:
- After the first monthly cron (or with >1 WFH type), the employee has multiple rows.
- That gives `IncorrectResultSizeDataAccessException`, and **every WFH approval fails**.

It also never selects by WFH type.

---

## 4. Flow Breaks — Deduction Paths (consume scheduler output)

### 4.1 Leave approval logic is inverted
- `LeaveTrackerServiceImpl.java:118-120`
  ```java
  if(!status.equalsIgnoreCase("Approved")) {
      leaveBalanceService.deductLeaveFromEmployee(employeeId, id);
  }
  ```
- **Rejecting** a leave deducts the balance. **Approving** does not.
- `deductLeaveFromEmployee` also forces `status = "APPROVED"` (`LeaveBalanceService.java:186`).
  The caller then overwrites it with the requested status.
- There is no status guard, so rejecting twice deducts twice.
- **Fix:**
  - Deduct only on `APPROVED`.
  - Allow the transition only from `Pending`.
  - Remove the status mutation from the balance service.

### 4.2 Leave deduct robustness
- `findByEmployeeIdAndLeaveTypeName(...).get()` has two failure modes:
  - No row → `NoSuchElementException`.
  - Multiple rows (duplicates or multiple years) → `IncorrectResultSizeDataAccessException`.

  It should filter by `year` and `isActive`.
- No balance check at deduct time. The check happens only at apply time, so two pending leaves can
  each pass validation and together drive the balance negative.
- Apply-time validation compares against `leaveBalance` only and ignores `carryForwardDays`.
- Days are counted as calendar days (`DAYS.between + 1`), so weekends and holidays are deducted.

### 4.3 WFH deduct / disburse
- `wfhTrackerRepository.findById(id).get()` throws before the `== null` check runs
  (`WfhBalanceServiceImpl.java:63,106`).
- If `employeeWfhBalance` is null → NPE (`:72,82`).
- `disburseWfhBalance` looks up `employeeWfhRepository.findById(employeeId)`, which is the
  **balance PK**, not the employee id. It also never writes a ledger row.
- `WFHBalanceController.disburseWfhBalance` (`:59`) is missing `@PathVariable` on both params, so
  the call is `findById(null)` → `IllegalArgumentException`. The endpoint is broken.

### 4.4 Ledger inconsistencies
- `createLeaveBalance` logs the INITIALIZATION transaction with `leaveType.getTotalDays()` instead of
  the prorated `leavesCount` (`LeaveBalanceService.java:217-218`). The ledger and the balance disagree.
- The WFH init ledger logs the unrounded `wfhCount` (double), but the balance stores
  `Math.round(wfhCount)` (int).
- No `balanceBefore`/`balanceAfter`/`reason`/`period` on transactions (commented out). You cannot
  audit or reconcile a disbursal run.

---

## 5. Prorata Calculator Notes

- `joinDate` is `LocalDate.now()` at the call site, not the employee's actual joining date. The
  `Employee` entity has **no joining-date field**, so backdated onboarding is prorated wrong.
- `initializeLeaveBalanceForNewLeaveType` prorates **all existing employees** from today.
  That is acceptable for a mid-year type launch, but it is subject to the same double-credit as 2.2.
- `getDisbursalFrequency()` being null → NPE. An unknown enum name → `IllegalArgumentException`.
  Either one aborts init for all types.
- The result is rounded to the nearest 0.25, while the Javadoc says "2 decimal places".
  The scheduler rounds to 2 decimals, so 10/yr monthly gives 0.83 × 12 = 9.96/yr (drift).
- `ProrataLeaveCalculator` and `ProrataWfhCalculator` are line-for-line duplicates, and the former
  ships a `main()` demo. Merge them into one calculator.
- There are no unit tests for either calculator or for the scheduler.

---

## 6. Security

- `/employee/leave-balance/disburse-{monthly,yearly,quarterly,half-yearly}-leave` and both
  `/initialize*` endpoints have **no auth**. `spring-security` is not on the classpath and
  `@CrossOrigin("*")` is set.
- Combined with 2.3 (non-idempotent), any caller can inflate every employee's balance at will.
- **Fix:** restrict these to an admin role or internal network. Make them idempotent per period, and
  have them take an explicit `period` parameter.

---

## 7. Pending Work — Prioritised Checklist

### P0 — wrong balances in prod
- [ ] Fix the inverted approval condition in `LeaveTrackerServiceImpl.updateLeaveStatus`, and add a
      `Pending`-only transition guard (4.1).
- [x] Make cron multi-tenant by iterating tenants and setting/clearing `TenantContext` (2.1).
- [ ] Change prorata to credit only the current period's share (2.2).
- [x] Add idempotency and cross-replica exclusion via the `disbursal_run` claim row (2.3).
- [ ] Replace the hard-coded 2026 cycle with the current or fiscal year (2.6).
- [ ] Rewrite WFH disbursal to upsert and increment, set `year`, and use floating division (3).
- [ ] Fix `deductWfhBalance` to look up by `(employeeId, wfhTypeName, year)` (3, 4.3).
- [ ] Point `company.service.url` at the prod value in prod, and fix the invalid
      `company.service.base.url` default (2.10).

### P1 — robustness
- [x] Add a real transaction boundary per leave type (separate bean / `TransactionTemplate`), and
      isolate per-type failures (2.4).
- [ ] Add a unique constraint `(employee_id, leave_type_name, year)` and make init an upsert (2.5).
- [ ] Exclude deleted/inactive employees, and add `joiningDate` to `Employee` (2.8, 5).
- [ ] Add a year-end rollover job: carry-forward, lapse, new-year rows (2.7).
- [ ] Add null-safe Company service handling, timeouts, retry, and catch-up of missed runs (2.9).
- [ ] Fix `disburseWfhBalance` (wrong repo lookup, missing `@PathVariable`, no ledger) (4.3).
- [ ] Add a balance check at deduct time, and include `carryForwardDays` in validation (4.2).
- [ ] Pin the cron timezone (2.11).

### P2 — auditability & hygiene
- [ ] Ledger: record the actual credited amount, `balanceBefore/After`, `reason`, `period`, `runId` (4.4).
- [ ] Add manual WFH trigger endpoints that take a `period` param, behind auth (3, 6).
- [ ] Add auth on all disburse/initialize endpoints (6).
- [ ] Merge the two prorata calculators, remove `main()`, and align the rounding policy (5).
- [ ] Unit tests: prorata edge cases (join on period start/end, leap year, Dec joiner), scheduler
      idempotency, multi-tenant iteration, and the duplicate-row handling.
- [ ] Use working-day calculation for leave/WFH deduction (exclude weekends/holidays).
- [ ] Fix the wrong log text in `disburseMonthlyLeave` ("quarterly").

---

## 8. Target Design (sketch)

```
@Scheduled(cron = "0 5 0 1 * ?", zone = "Asia/Kolkata")   // single trigger, monthly
@SchedulerLock(name = "disbursal")                        // one pod only
void runDisbursals() {
  for tenant in tenants:
    TenantContext.set(tenant)
    try:
      if (isJan1) yearEndRollover(tenant)               // before any credit
      for freq in frequenciesDueToday():                // MONTHLY always; QUARTERLY on 1,4,7,10 …
        for type in companyService.typesFor(tenant, freq):
          periodKey = periodKey(freq, today)            // "2026-10", "2026-Q4"
          if (!disbursalRunRepo.tryInsert(tenant, kind, type, periodKey)) continue   // idempotent
          txTemplate.execute(() -> creditActiveEmployees(type, freq, periodKey))      // upsert + ledger
          markRunSuccess(...)
    catch e: markRunFailed(...); alert
    finally: TenantContext.clear()
}
```

Joining: credit `prorate(currentPeriod, joiningDate)` only. Future periods come from the run above.
On startup, and hourly, re-run any `disbursal_run` that is missing or failed for the current period.
