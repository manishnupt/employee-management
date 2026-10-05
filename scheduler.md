# Disbursal Scheduler — Status & Open Issues

Scope: leave and WFH balance disbursal in `employee-management` — the cron jobs, prorata on joining,
year-end rollover, and the approval paths that deduct from the balances. Branch: `feature/proratacalc`.

**How it all works is in [scheduler-flow.md](scheduler-flow.md).** This file lists only what is
still open: deployment prerequisites, data fixes and known gaps.

Related docs: [leave.md](leave.md), [wfh.md](wfh.md), [employee-leave-balance.md](employee-leave-balance.md).

---

## 1. Status

The correctness bugs found in the original audit are fixed in code. The scheduler is **not yet safe
to deploy** until the DB changes in [section 2](#2-before-deploying--db-changes) are run, and
balances written by the old code still need the data fixes in [section 3](#3-data-fixes-for-existing-balances).

| Original problem | Status |
|---|---|
| Cron ran with no tenant context; only the default tenant was credited | Fixed |
| Prorata on joining credited the rest of the year, and the scheduler credited it again | Fixed |
| No idempotency or cross-replica lock | Fixed (`disbursal_run` claim) |
| Per-type disbursal not transactional; one type failing aborted the rest | Fixed |
| Hard-coded 2026 cycle | Fixed (current calendar year) |
| Deleted / inactive employees and current-period joiners were credited | Fixed |
| No year-end rollover, carry-forward or lapse | Fixed for leave. WFH has none (5.4) |
| WFH scheduler inserted a row every run, never set `year`, truncated to 0 | Fixed |
| WFH deduction failed once an employee had more than one balance row | Fixed |
| WFH refund endpoint broken | Fixed |
| Leave approval logic inverted (reject deducted, approve did not) | Fixed |
| Leave deduct: no balance re-check, no lock, calendar days | Fixed |
| Ledger did not record the real amount, before/after or reason | Fixed for leave and WFH |
| Manual disburse / initialize endpoints are unauthenticated | **Open** (4.1) |

---

## 2. Before deploying — DB changes

`ddl-auto=none` and there is no startup initializer, so the app creates nothing. Run all of this in
**every tenant database**. A tenant onboarded later needs the same as part of its onboarding.

```sql
-- 1. Period claim table. Without it nothing is disbursed for the tenant.
CREATE TABLE IF NOT EXISTS disbursal_run (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         VARCHAR(100)     NOT NULL,
    kind              VARCHAR(20)      NOT NULL,   -- LEAVE / WFH / LEAVE_ROLLOVER
    type_name         VARCHAR(255)     NOT NULL,
    frequency         VARCHAR(20)      NOT NULL,
    period_key        VARCHAR(20)      NOT NULL,   -- 2026-10, 2026-Q4, 2026-H2, 2026
    days_per_employee DOUBLE PRECISION NOT NULL,
    created_at        TIMESTAMP        NOT NULL DEFAULT now(),
    CONSTRAINT uk_disbursal_run UNIQUE (tenant_id, kind, type_name, period_key)
);

-- 2. Fractional carry-forward (was integer)
ALTER TABLE employee_leave_balance ALTER COLUMN carry_forward_days TYPE DOUBLE PRECISION;

-- 3. Leave ledger detail (nullable: older rows have none)
ALTER TABLE leave_transactions
    ADD COLUMN IF NOT EXISTS year           INTEGER,
    ADD COLUMN IF NOT EXISTS balance_before DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS balance_after  DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS reason         VARCHAR(255);

-- 4. WFH ledger detail. Without these every WFH credit, debit and init fails on insert.
ALTER TABLE wfh_transactions
    ADD COLUMN IF NOT EXISTS year           INTEGER,
    ADD COLUMN IF NOT EXISTS balance_before DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS balance_after  DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS reason         VARCHAR(255);

-- 5. Rollover and disbursal look up by year
CREATE INDEX IF NOT EXISTS idx_elb_year_active ON employee_leave_balance (year, is_active);

-- 6. New leave ledger types LAPSE / CARRY_FORWARD_OUT: if Hibernate once generated a CHECK
--    constraint on transaction_type, inserts of the new values fail. Find and drop it:
SELECT conname FROM pg_constraint WHERE conrelid = 'leave_transactions'::regclass AND contype = 'c';
-- ALTER TABLE leave_transactions DROP CONSTRAINT <name>;
```

The `uk_disbursal_run` constraint is required: the claim's `ON CONFLICT` targets exactly those columns.

---

## 3. Data fixes for existing balances

The code fixes do not correct what the old code already wrote. Decide each of these per tenant.

### 3.1 Balances initialized under the old prorata
Anyone who joined before the prorata fix was credited the **rest of the year** at joining, and the
scheduler then credited the same periods again. Those rows are still inflated, and the scheduler
keeps adding to them. They need a one-off correction.

### 3.2 Leaves decided under the inverted approval logic
Leaves **rejected** under the old code had balance deducted; leaves **approved** did not. Find them
by comparing `leave_tracker.status` with the `DEBIT` rows in `leave_transactions`
(`reason = 'Leave #<id>'`; older rows have no reason).

### 3.3 Legacy WFH rows with `year = 0`
Rows written by the old WFH scheduler have `year = 0`. They are now ignored by reads, deduction and
disbursal, so a balance that lives only in those rows disappears from the employee's view.

```sql
SELECT employee_id, wfh_type_name, count(*) AS row_count, sum(wfh_balance) AS days
FROM employee_wfh_balance WHERE year = 0 GROUP BY employee_id, wfh_type_name;
```

Each old row holds one period's credit, not a running total. Either fold what is genuinely owed into
the employee's current-year row, or delete the rows and let the scheduler credit from the next period.

---

## 4. Open issues — scheduler and platform

### 4.1 Endpoints are unauthenticated
- `spring-security` is not on the classpath and `@CrossOrigin("*")` is set. This covers every
  `/disburse-*`, `/initialize*`, `/rollover-leave-year` and the WFH refund endpoint.
- The disburse and rollover endpoints are idempotent per period, so they can no longer be used to
  inflate balances. **The `/initialize*` endpoints are not** (4.2): each call adds a row.
- **Fix:** restrict to an admin role or the internal network.

### 4.2 Duplicate balance rows / init is not idempotent
- There is no unique constraint on `(employee_id, leave_type_name, year)` or the WFH equivalent.
- `/initialize/{employeeId}` and `/initialize-for-new-*-type` always **insert**. A second call creates
  a second row for the same employee, type and year.
- Effect of a duplicate leave row:
  - Scheduler: `Collectors.toMap` throws, so **that leave type** is not credited for the tenant this
    period. The claim rolls back, so it can be retried once the duplicate is removed.
  - Rollover: fails loudly, and the tenant's leave disbursals stay blocked until it is removed.
  - Approval: `findActiveForUpdate` returns more than one row and throws.
- Effect of a duplicate WFH row: the scheduler credits the oldest and logs a WARN; reads and
  deduction use both rows.
- **Fix:** add the unique constraints and make init an upsert.

### 4.3 Company service calls: no timeout, retry or catch-up
- `RestTemplate` has no connect or read timeout. A hung Company service or tenant API blocks the
  single scheduler thread.
- No retry. A missed run (pod down at 00:00, Company service down, tenant API down) is **never caught
  up** automatically; someone must call the manual `/disburse-*` endpoint, and only within the same period.
- **Fix:** timeouts, retry with backoff, and a startup or hourly check driven by `disbursal_run`
  ("is `2026-10` done for tenant X?").

### 4.4 Config points at pre-prod / invalid default
- `company.service.url=https://api.pp.hrms.work` is hard-coded in `application.properties` and not
  overridden for prod, so **prod cron and rollover read leave/WFH types from pre-prod**.
- `company.service.base.url` defaults to `http://localhost:82323`, which is not a valid port. The
  pre-prod chart sets `COMPANY_BASE_URL`; `charts/emp-mgmt/values/prod.yaml` does not. Without it every
  init call fails, `createEmployee` swallows the error, and the joiner has **no balances**.
- `tenant.config.api.url` reads `${TENANT_DB_URL}` and defaults to pre-prod. The prod chart sets
  `TENANT_DB_CONFIG_URL`, a different name. Check that prod actually resolves the prod tenant list.
- Init and cron use two different properties for the same Company service.
- **Fix:** one env-backed property per service, no hard-coded host, fail fast at startup if missing.

### 4.5 Timezone
- Cron, `LocalDate.now()` and `Year.now()` use the JVM default zone. In a container that is usually
  UTC, so jobs fire at 05:30 IST, and prorata / "which year" checks near midnight on 31 Dec can land
  on the wrong side.
- **Fix:** `@Scheduled(cron = …, zone = "Asia/Kolkata")` and a shared `Clock`.

### 4.6 Smaller gaps

| Gap | Effect |
|---|---|
| No joining-date field on `Employee`; `createdAt` stands in | Backdated onboarding is prorated from the day the record was created. |
| Init failure on joining is swallowed | The joiner gets nothing for the join period and is first credited at the next period start. |
| Only `Active` (or blank) job status accrues | Tenants using other values for people who should accrue (e.g. `Probation`) must add them to `DisbursalEligibility`. |
| Going inactive mid-period | The employee keeps what was already credited for that period. |
| Frequency changed mid-period (e.g. monthly → quarterly in October) | The type can be credited under both `2026-10` and `2026-Q4`. |
| New type launched mid-period, then a manual `/disburse-*` for that period | Init prorates it for everyone, and the manual call credits the full period on top (no claim row exists for a new type). |
| Calendar year only | A tenant fiscal year (e.g. Apr–Mar) is not supported. |
| `employeeRepository.findAll()` on every type | All employees are loaded per type per run; does not scale to large tenants. |

---

## 5. Open issues — leave

| # | Gap | Effect |
|---|---|---|
| 5.1 | Leave credit is rounded to 2 decimals per period | 10/yr monthly credits 0.83 × 12 = 9.96 per year. |
| 5.2 | Public holidays are deducted | No holiday calendar in this service. `WorkingDays` is the one place to add it. Sat/Sun as the weekend is fixed, not per tenant. |
| 5.3 | Pending leaves are not reserved | An employee can apply for more than their balance across several pending requests. The excess is refused at approval, not at apply. |
| 5.4 | No cancel or refund for an approved leave | Once approved, the status cannot change and the days cannot be returned. |
| 5.5 | Deducted from the year of approval | A leave applied in December and approved in January, or spanning two years, comes entirely out of the approval year. |
| 5.6 | Two approvals of the **same leave** at the same instant | Both can pass the `Pending` check and deduct twice. The balance row is locked, the `leave_tracker` row is not (no version column). |
| 5.7 | Rollover checks `deleted` only | An inactive, non-deleted employee still gets a new-year row with their carry-forward, but no further credits. |
| 5.8 | `maxCarryForwardDays` is optional | The Company service must send it for a cap to apply. Without it, all remaining days carry forward. |
| 5.9 | Leaves applied before the working-day change | Validated on calendar days, deducted on working days when approved (never more than was validated). |

---

## 6. Open issues — WFH

| # | Gap | Effect |
|---|---|---|
| 6.1 | `updateWFHStatus` is not transactional | The deduction commits before the status is saved. If the status save fails, the request stays `PENDING` with the balance already deducted, and a retry deducts again. |
| 6.2 | Status can be changed after approval | An `APPROVED` request can be set to `REJECTED` or anything else with no refund. Only a second approval is blocked. The refund endpoint must be called while the request is still `APPROVED`. |
| 6.3 | No balance check at apply | A request for more than the balance is accepted and only refused at approval. |
| 6.4 | No WFH year-end rollover | Unused days do not carry forward; last year's rows stay `is_active = true` but are ignored. |
| 6.5 | Calendar days are deducted | Weekends and holidays inside a WFH request consume balance. Leave uses working days. |
| 6.6 | A WFH request has no WFH type | Deduction draws across all the employee's types in type-name order. A refund goes entirely to the first type, even if the deduction came from several. |
| 6.7 | Refund goes to the current year | A request deducted last year is refunded into this year's balance. |
| 6.8 | No row lock on WFH deduct or refund | Two approvals, or two refund calls, at the same instant can both pass their checks. |
| 6.9 | Join credit rounded to a whole day | A joiner's first credit can be off by up to half a day. |
| 6.10 | `transaction_type` is a free string | Leave uses an enum; WFH does not. |
| 6.11 | `days_per_employee` in `disbursal_run` varies | Expected with the whole-day spread (e.g. 5,4,5,4); it is that period's amount, not a constant. |

---

## 7. Pending work — checklist

### P0 — needed for a correct prod rollout
- [ ] Run the DB changes in section 2 in every tenant database.
- [ ] Correct balances initialized under the old prorata (3.1).
- [ ] Correct balances for leaves decided under the inverted logic (3.2).
- [ ] Clean up legacy `year = 0` WFH rows (3.3).
- [ ] Point `company.service.url` at prod, set `COMPANY_BASE_URL` in the prod chart, and verify the
      tenant API env var name (4.4).
- [ ] Add auth on all disburse / initialize / rollover / refund endpoints (4.1).

### P1 — robustness
- [ ] Unique constraint on `(employee_id, type_name, year)` for both balance tables; make init an upsert (4.2).
- [ ] `RestTemplate` timeouts, retry, and catch-up of missed runs (4.3).
- [ ] Pin the cron timezone and use a shared `Clock` (4.5).
- [ ] Make `updateWFHStatus` transactional and add a `PENDING`-only transition guard like leave (6.1, 6.2).
- [ ] Add `joiningDate` to `Employee` and use it for prorata and the join-period check (4.6).
- [ ] Lock or version `leave_tracker` / `wfh_tracker` rows on approval (5.6, 6.8).

### P2 — policy and hygiene
- [ ] WFH: balance check at apply, working-day counting, a type on the request (6.3, 6.5, 6.6).
- [ ] WFH year-end rollover, if carry-forward is wanted (6.4).
- [ ] Holiday calendar for leave deduction (5.2).
- [ ] Reserve pending leaves at apply time (5.3).
- [ ] Cancel / refund path for approved leave (5.4).
- [ ] Align leave credit rounding so the year adds up to `totalDays` (5.1).
- [ ] Merge the two prorata calculators (line-for-line duplicates) and remove `main()` from
      `ProrataLeaveCalculator`.
- [ ] Tests still missing: multi-tenant iteration, leave scheduler idempotency, the row-lock query
      against real Postgres (covered only through mocks).

---

## 8. Change log

| Commit | Change |
|---|---|
| `04c5dae` disbursal logic v1 | Multi-tenant cron (`TenantJobRunner`), `disbursal_run` claim, per-type transactions. |
| `8720651` phase 1 refactoring | `TenantRegistry` reads tenants from the tenant microservice; `disbursal_run` created manually. |
| `909d00d` rollover handling | Leave year-end rollover, carry-forward / lapse ledger entries, current-year-only disbursal and deduction. |
| *(uncommitted)* | Prorata credits the join period only; cycle from the current calendar year; `DisbursalEligibility`. |
| *(uncommitted)* | WFH scheduler rewritten: increments the current-year row, whole-day spread, manual `/disburse-*-wfh` endpoints. WFH read, deduct and refund use current-year rows; `EmployeeWfhRepository` removed; WFH ledger detail. |
| *(uncommitted)* | Leave approval fixed (deduct only on `Approved`, `Pending`-only transitions); balance re-check under a row lock; working-day counting (`WorkingDays`). |
