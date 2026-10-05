# Leave & WFH Balance — Flow Reference

How leave and WFH balances are credited, prorated, deducted and closed in `employee-management`.
This describes the code as it stands on `feature/proratacalc`. Open problems and pending
deployment steps are in [scheduler.md](scheduler.md).

Contents:
[1. Overview](#1-overview) ·
[2. Data model](#2-data-model) ·
[3. Shared rules](#3-shared-rules) ·
[4. Prorata on joining](#4-prorata-on-joining) ·
[5. Scheduler run](#5-scheduler-run) ·
[6. Leave flow](#6-leave-flow) ·
[7. WFH flow](#7-wfh-flow) ·
[8. Leave vs WFH](#8-leave-vs-wfh-at-a-glance) ·
[9. Endpoints](#9-endpoints) ·
[10. Code map](#10-code-map)

---

## 1. Overview

A balance changes in four ways. Leave and WFH follow the same shape; the differences are in
[section 8](#8-leave-vs-wfh-at-a-glance).

```
 ┌─ JOINING ──────────────┐   ┌─ PERIOD START (cron) ──────┐   ┌─ APPROVAL ────────────┐   ┌─ 1 JAN ────────────────┐
 │ prorated share of the  │   │ one period's share to every │   │ days of the request   │   │ leave: carry forward / │
 │ join period only       │   │ eligible employee           │   │ taken off the balance │   │ lapse, open new year   │
 │ ledger: INITIALIZATION │   │ ledger: CREDIT              │   │ ledger: DEBIT         │   │ WFH: fresh row, no     │
 └────────────────────────┘   └─────────────────────────────┘   └───────────────────────┘   │ carry forward          │
                                                                                            └────────────────────────┘
```

The rule that ties joining and the scheduler together: **joining credits only the period the employee
joins in; the scheduler credits every later period and skips anyone who joined in the current one.**
Each period is therefore credited exactly once per employee.

Leave and WFH types (name, `totalDays` per year, disbursal frequency, carry-forward settings) are
owned by the **Company service**. This service only reads them.

---

## 2. Data model

| Table | One row per | Key columns |
|---|---|---|
| `employee_leave_balance` | employee + leave type + year | `leave_balance` (accrued in the current period, decimal), `carry_forward_days` (kept from earlier periods or last year, decimal), `remaining_days` (= the two added, recomputed on save), `year`, `is_active` |
| `employee_wfh_balance` | employee + WFH type + year | `wfh_balance` (**whole days**), `carry_forward_days` (always 0 today), `remaining_days`, `year`, `is_active` |
| `leave_transactions` | every leave balance change | `transaction_type` (enum), `days`, `year`, `balance_before`, `balance_after`, `reason` |
| `wfh_transactions` | every WFH balance change | `transaction_type` (string), `days`, `year`, `balance_before`, `balance_after`, `reason` |
| `disbursal_run` | tenant + kind + type + period | `kind` (`LEAVE` / `WFH` / `LEAVE_ROLLOVER`), `type_name`, `frequency`, `period_key`, `days_per_employee` |
| `leave_tracker` / `wfh_tracker` | leave / WFH request | dates, `status`; WFH also `deduct_wfh_balance` |

- **Available days** = `leave_balance + carry_forward_days` (leave) or `wfh_balance + carry_forward_days`
  (WFH). This is what validation checks and what the ledger records as before/after.
- Reads, deduction and disbursal all use only rows with **`year = current year AND is_active = true`**.
  Rows of other years are ignored.
- `days` in a ledger row is always positive; the transaction type gives the direction.

---

## 3. Shared rules

### 3.1 Periods

The year is the **calendar year** (1 Jan – 31 Dec). A fiscal year is not supported.

| Frequency | Periods / year | Cron (00:00, JVM timezone) | Period key example | Period start |
|---|---|---|---|---|
| `MONTHLY` | 12 | `0 0 0 1 * ?` — 1st of every month | `2026-10` | 1 Oct |
| `QUARTERLY` | 4 | `0 0 0 1 1,4,7,10 ?` | `2026-Q4` | 1 Oct |
| `HALF_YEARLY` | 2 | `0 0 0 1 1,7 ?` | `2026-H2` | 1 Jul |
| `YEARLY` | 1 | `0 0 0 1 1 ?` | `2026` | 1 Jan |

`DisbursalFrequency.periodKey(date)` and `periodStart(date)` produce these.

### 3.2 Who accrues (`DisbursalEligibility`)

| Check | Rule |
|---|---|
| Soft-deleted | `deleted = true` never accrues. |
| Job status | Accrues when `jobStatus` is `Active` (case-insensitive, trimmed). **Blank or null counts as active.** Any other value does not accrue. |
| Joined in this period | Skipped when the employee's `createdAt` date is **on or after** the period start. Joining already credited that period's prorated share. |

- The scheduler applies all three checks.
- Initializing a **new type** for existing employees applies the first two only.
- `Employee` has no joining-date field: `createdAt` is the joining date everywhere, for both prorata
  and the join-period check, so the two always agree.

### 3.3 Exactly-once per period (`disbursal_run`)

Before crediting a type for a period, the scheduler inserts a claim row:

```sql
INSERT INTO disbursal_run (tenant_id, kind, type_name, frequency, period_key, ...)
VALUES (...) ON CONFLICT (tenant_id, kind, type_name, period_key) DO NOTHING
```

- **1 row inserted** → this run owns the period and credits it.
- **0 rows** → already disbursed, skip.
- The claim and the crediting are in **one transaction per type**. A failure rolls back both, so the
  period can be retried. A second replica blocks on the uncommitted claim, then gets 0 rows.
- This is why a manual `/disburse-*` call is safe to repeat: it credits only if the cron missed the period.

---

## 4. Prorata on joining

### 4.1 When it runs

| Trigger | Method | Who gets a balance |
|---|---|---|
| `createEmployee` (automatic) or `POST …/initialize/{employeeId}` | `initializeLeaveBalanceForNewEmployee`, `initializeWfhBalanceForNewEmployee` | That employee, for **every** type the Company service returns from `/leave-types` and `/wfh-types` |
| `POST …/initialize-for-new-leave-type`, `…/initialize-for-new-wfh-type` | `initialize…ForNew{Leave,Wfh}Type` | **Every active employee**, for the one new type |

In all four cases the join date is **today**, and the cycle is 1 Jan – 31 Dec of today's year.
A new type launched mid-period is therefore prorated for everyone from the launch date.

`createEmployee` logs and swallows an init failure: the employee is still created, with no balances.

### 4.2 Formula

`ProrataLeaveCalculator.calculateProrataLeaves` and `ProrataWfhCalculator.calculateProrataWfh` are
the same calculation:

```
perPeriod      = totalDays / periodsPerYear
joinPeriod     = the month / quarter / half-year / year containing the join date
remainingDays  = days from join date to end of joinPeriod, both inclusive
periodDays     = days in joinPeriod

prorated       = perPeriod × remainingDays / periodDays
result         = prorated rounded to the nearest 0.25
```

- Only the join period is counted. Later periods are **not** included; the scheduler credits them.
- Joining on the first day of a period gives the full period share.
- A join date before the cycle start is treated as the cycle start. After the cycle end gives 0.

### 4.3 Worked example — 24 days/year, joins 16 Aug 2026

| Frequency | Per period | Join period | Remaining / period days | Init credit | Scheduler adds later | Year total |
|---|---|---|---|---|---|---|
| `MONTHLY` | 2 | August | 16 / 31 | **1.0** | 2 × 4 (Sep–Dec) | 9.0 |
| `QUARTERLY` | 6 | Q3 (Jul–Sep) | 46 / 92 | **3.0** | 6 × 1 (Q4) | 9.0 |
| `HALF_YEARLY` | 12 | H2 (Jul–Dec) | 138 / 184 | **9.0** | none | 9.0 |
| `YEARLY` | 24 | 2026 | 138 / 365 | **9.0** (9.07 rounded) | none | 9.0 |

### 4.4 What gets stored

| | Leave | WFH |
|---|---|---|
| Balance | The 0.25-rounded value as is (e.g. `1.0`, `3.25`) | Rounded again to a **whole day** with `Math.round` (0.5 rounds up) |
| Row | `leave_balance = result`, `carry_forward_days = 0`, `year = current`, `is_active = true` | `wfh_balance = whole days`, same other fields |
| Ledger | `INITIALIZATION`, `days` = credited amount, `balance_before = 0` | `INITIALIZATION`, `days` = the whole days stored |
| Ledger `reason` | `NEW_EMPLOYEE_INITIALIZATION` or `NEW_LEAVE_TYPE_INITIALIZATION` | `NEW_EMPLOYEE_INITIALIZATION` or `NEW_WFH_TYPE_INITIALIZATION` |

WFH example: 12 days/year monthly (1 per month), joins 16 Aug → 1 × 16/31 = 0.52 → 0.5 → **1** day.
Joins 25 Aug → 1 × 7/31 = 0.23 → 0.25 → **0** days.

Init always **inserts** a new row. Running it twice for the same employee and type creates a duplicate.

---

## 5. Scheduler run

`LeaveDisbursalSchedulerService` and `WFHDisbursalSchedulerService` have the same structure.

```
cron fires (per frequency)
└─ TenantJobRunner.forEachTenant
   ├─ TenantRegistry: GET ${tenant.config.api.url}  → tenant list, fetched fresh every run
   └─ for each tenant:  TenantContext.set(tenant) … finally TenantContext.clear()
      │
      ├─ [leave only] LeaveYearEndRolloverService.rolloverIfNeeded(tenant, currentYear)   → section 6.5
      │
      ├─ GET {company.service.url}/leave-types/schedule/{frequency}     (X-Tenant-Id: tenant)
      │      or /wfh-types/schedule/{frequency}
      │      empty or null body → log WARN, stop for this tenant
      │
      └─ for each type returned:                         ← one transaction per type
           ├─ work out this period's days                (sections 6.2 / 7.2)
           ├─ claim disbursal_run(tenant, kind, type, periodKey)
           │     0 rows → already disbursed, skip
           ├─ load eligible employees                    (section 3.2)
           ├─ load this year's active balance rows for the type
           ├─ per employee: add days to the row (create the row at 0 if missing)
           │                write a CREDIT ledger row, reason "<FREQUENCY> disbursal <periodKey>"
           └─ saveAll balances + ledger
```

Failure handling:

| What fails | Result |
|---|---|
| Tenant API down or non-2xx | The whole cron run is skipped. Nothing is claimed, so a later manual run can still credit the period. |
| One tenant throws (DB down, Company service error) | Logged; the loop continues with the next tenant. |
| One type throws | Its claim and credits roll back; the other types still run. |
| Leave rollover throws | No leave is credited for that tenant in this run. |
| Pod down at 00:00 | The period is **not** credited until someone calls the manual endpoint. Spring cron does not backfill. |

The manual `/disburse-*` endpoints call the same code for **one tenant**, taken from the request's
`X-Tenant-Id`, and always for the period containing **today**.

---

## 6. Leave flow

### 6.1 Lifecycle

```
join ──► INITIALIZATION (prorated)
          │
          ▼
period start ──► carry forward / LAPSE what is left of earlier periods
                 CREDIT (totalDays / periods)      repeated every period
          │
apply ──► validated against available days, status Pending   (no balance change)
          │
approve ──► DEBIT (working days, carry-forward first)
          │
1 Jan ──► LAPSE / CARRY_FORWARD_OUT on the old row, CARRY_FORWARD into the new row
```

### 6.2 Periodic credit

```
daysToDisburse = round(totalDays / periodsPerYear, 2 decimals)
```

| `totalDays` | Monthly | Quarterly | Half-yearly | Yearly |
|---|---|---|---|---|
| 24 | 2.0 | 6.0 | 12.0 | 24.0 |
| 18 | 1.5 | 4.5 | 9.0 | 18.0 |
| 10 | 0.83 (9.96 over the year) | 2.5 | 5.0 | 10.0 |

The amount is added to `leave_balance` of the employee's current-year row. If the employee has no
row for that type and year, one is created starting at 0.

**Period boundary — carry forward or lapse.** Before the credit, in the same transaction, the
type's carry-forward rule is applied to whatever is left on each current-year row of that type
(`remaining = leave_balance + carry_forward_days`). It is the same rule the year-end rollover uses,
applied at every period start, so a month, quarter or half-year boundary is treated like 31 Dec.

| Type setting | Kept | Lapsed |
|---|---|---|
| `carryForward = false` | 0 | all of `remaining` |
| `carryForward = true`, no `maxCarryForwardDays` | all of `remaining` | 0 |
| `carryForward = true`, with a cap | `min(remaining, cap)` | the rest |

- What is kept moves to `carry_forward_days` and `leave_balance` is reset to 0, so after the credit
  `leave_balance` is this period's accrual and `carry_forward_days` is what came from earlier periods.
- What is lapsed gets a `LAPSE` ledger row, `year` = current, reason e.g.
  `Leave type does not allow carry forward (before 2026-11)`.
- It applies to **every** row of the type, including employees who no longer accrue (inactive, deleted).
- A row belonging to someone who joined in the period being credited is left alone: that balance
  is their prorated share of this period.
- A row at 0 or below is left as it is.
- If a period was missed, the next run that does happen applies the rule once.

Example — 24 days/year monthly, 1.5 days unused at the end of January:

| Type | After the 1 Feb run | Ledger |
|---|---|---|
| `carryForward = false` | `leave_balance = 2`, `carry_forward_days = 0` | `LAPSE 1.5` (1.5 → 0), `CREDIT 2` (0 → 2) |
| `carryForward = true`, no cap | `leave_balance = 2`, `carry_forward_days = 1.5` | `CREDIT 2` (1.5 → 3.5) |
| `carryForward = true`, cap 1 | `leave_balance = 2`, `carry_forward_days = 1` | `LAPSE 0.5` (1.5 → 1), `CREDIT 2` (1 → 3) |

### 6.3 Applying for leave — `POST /employees/{employeeId}/leave-tracker`

`LeaveTrackerServiceImpl.applyLeave`. Nothing is deducted or reserved at this point.

1. Start and end dates must both be present, and the end date must not be before the start date.
2. `days = WorkingDays.between(start, end)` — both dates inclusive, **Saturdays and Sundays not
   counted**. A range with no working days is refused.
3. The employee must have a current-year active balance row for that leave type.
4. `days` must be ≤ available days (`leave_balance + carry_forward_days`), otherwise
   "Insufficient leave balance for the requested leave type".
5. The leave is saved as `Pending` and an action item is created for the assigned manager.

### 6.4 Approving / rejecting — `PUT /employees/{employeeId}/leave-tracker/{id}/status?status=…`

`LeaveTrackerServiceImpl.updateLeaveStatus`, one transaction covering the deduction and the status change.

| Current status | Requested status | Result |
|---|---|---|
| `Pending` | `Approved` | Balance deducted, status saved. |
| `Pending` | anything else (e.g. `Rejected`) | Status saved, **no deduction**. |
| same as requested | — | No-op. A retried request cannot deduct twice. |
| already decided | a different status | Refused: "Leave is already … and cannot be changed to …". |

Status comparison ignores case. The status is stored as the caller sent it.

**How the deduction works** (`LeaveBalanceService.deductLeaveFromEmployee`):

1. `days = WorkingDays.between(start, end)` — the same count as at apply time.
   0 working days → nothing is deducted and no ledger row is written.
2. The employee's current-year active row for the leave type is read **with a row lock**
   (`findActiveForUpdate`, `PESSIMISTIC_WRITE`), held until the transaction ends. Two approvals for
   the same employee and type are processed one after the other.
3. The balance is **re-checked**: if `days > available`, the approval is refused with "Insufficient …
   balance to approve this leave". Nothing is deducted and the leave stays `Pending`.
4. `EmployeeLeaveBalance.deductDays(days)` takes **carried-forward days first**, then this year's accrual:

   ```
   fromCarryForward    = min(carry_forward_days, days)
   carry_forward_days -= fromCarryForward
   leave_balance      -= days − fromCarryForward
   ```
5. A `DEBIT` ledger row is written with `reason = "Leave #<id>"`.

Example — row has `carry_forward_days = 3`, `leave_balance = 10`; a Thu–Tue leave is approved:

| | Value |
|---|---|
| Calendar days | 6 (Thu, Fri, Sat, Sun, Mon, Tue) |
| Working days deducted | **4** |
| From carry-forward | 3 → `carry_forward_days = 0` |
| From accrual | 1 → `leave_balance = 9` |
| Ledger | `DEBIT`, `days = 4`, `balance_before = 13`, `balance_after = 9` |

Notes:
- The deduction always comes from the year in which the leave is **approved**, even if the leave
  dates fall in another year.
- Public holidays are counted as working days.
- There is no cancel or refund path for an approved leave.

### 6.5 Year-end rollover (`LeaveYearEndRolloverService`)

**When:** every leave disbursal, cron or manual, first calls `rolloverIfNeeded(tenant, currentYear)`.
On 1 Jan the first job to run does the rollover and the rest find it done. It can also be triggered
with `POST /employee/leave-balance/rollover-leave-year`.

**Once per tenant per year:** it claims `disbursal_run (kind = LEAVE_ROLLOVER, type_name = '*',
period_key = <new year>)` in the same transaction as the whole rollover. All or nothing per tenant.

**Steps:**

1. Fetch all leave types (union of the four `/leave-types/schedule/{freq}` responses). If the union
   is empty the rollover aborts, so a Company service outage cannot lapse every balance.
2. Load every active row with `year < target year`.
3. For each one, with `remaining = leave_balance + carry_forward_days`:

   | Case | Carried into new year | Lapsed | New-year row |
   |---|---|---|---|
   | Type has `carryForward = true` | `min(remaining, maxCarryForwardDays)`; all of it if no cap is sent | the rest | yes |
   | Type has `carryForward = false` | 0 | all | yes, at 0 |
   | Type no longer returned by Company service | 0 | all | no |
   | Employee deleted | 0 | all | no |
   | `remaining < 0` | 0 (deficit not carried, WARN logged) | 0 | as above |

4. The old row is kept with `is_active = false`.
5. The new-year row starts at `leave_balance = 0`, `carry_forward_days = <carried>`. If a new-year row
   already exists, the carry is added to it.

Example — type allows carry-forward with a cap of 5, employee closes 2026 with 8 days:

| Ledger row | `year` | `days` | before → after |
|---|---|---|---|
| `LAPSE` ("Exceeds carry-forward cap of 5.0 day(s)") | 2026 | 3 | 8 → 5 |
| `CARRY_FORWARD_OUT` ("Carried forward to 2027") | 2026 | 5 | 5 → 0 |
| `CARRY_FORWARD` ("Carried forward from 2026") | 2027 | 5 | 0 → 5 |

After the rollover, the 1 Jan disbursal adds the first period's credit to the new row.

### 6.6 Leave ledger (`leave_transactions`)

| Type | Written by | `year` | `reason` |
|---|---|---|---|
| `INITIALIZATION` | joining / new leave type | current | `NEW_EMPLOYEE_INITIALIZATION` / `NEW_LEAVE_TYPE_INITIALIZATION` |
| `CREDIT` | scheduler | year credited | e.g. `MONTHLY disbursal 2026-10` |
| `DEBIT` | approval | year of approval | `Leave #<id>` |
| `LAPSE` | scheduler (period boundary), rollover | current year / closing year | why it lapsed |
| `CARRY_FORWARD_OUT` | rollover | closing year | `Carried forward to <year>` |
| `CARRY_FORWARD` | rollover | new year | `Carried forward from <year>` |

Reconciliation, per employee, type and year:

```
INITIALIZATION + CREDIT + CARRY_FORWARD − DEBIT − LAPSE − CARRY_FORWARD_OUT = closing balance
```

---

## 7. WFH flow

### 7.1 Lifecycle

```
join ──► INITIALIZATION (prorated, rounded to a whole day)
          │
          ▼
period start ──► CREDIT (whole days for this period)     repeated every period
          │
apply ──► saved as PENDING with a deductWfhBalance flag   (no balance check, no balance change)
          │
approve ──► flag true:  DEBIT (calendar days), drawn across the employee's WFH types
            flag false: approved, balance untouched
          │
refund ──► CREDIT of the days an approved request deducted (once per request)
          │
1 Jan ──► scheduler starts a fresh row for the new year; last year's row is ignored
```

### 7.2 Periodic credit — whole days

WFH balances are whole numbers, so the annual total is spread so that the year adds up to exactly
`totalDays` (`DisbursalFrequency.wholeDaysForPeriod`):

```
periodIndex = number of this period in the year (1-based)
credit      = round(totalDays × periodIndex / periods) − round(totalDays × (periodIndex − 1) / periods)
```

| `totalDays`, frequency | Credit per period, in order | Year total |
|---|---|---|
| 12, monthly | 1,1,1,1,1,1,1,1,1,1,1,1 | 12 |
| 10, monthly | 1,1,1,0,1,1,1,1,1,0,1,1 | 10 |
| 18, quarterly | 5,4,5,4 | 18 |
| 5, half-yearly | 3,2 | 5 |

- A period that works out to 0 is still claimed in `disbursal_run`; nothing is credited and no
  `CREDIT` row is written. The period-boundary step below still runs.
- The credit is added to the employee's current-year active row for that type. A row is created
  (year = current, active) only when none exists.
- If an employee has duplicate rows for a type and year, the **oldest** is credited and a WARN is logged.
- A type the Company service returns without a name is skipped with an ERROR log. The name is read
  from either `wfhType` or `name` in the response.

**Period boundary — carry forward or lapse.** Same rule as leave (section 6.2), applied before the
credit in the same transaction, to every current-year row of the type:

| Type setting (from `/wfh-types/schedule/{freq}`) | Kept | Lapsed |
|---|---|---|
| `carryForward = false` | 0 | all of `wfh_balance` |
| `carryForward = true`, no `maxCarryForwardDays` | all | 0 |
| `carryForward = true`, with a cap | `min(wfh_balance, floor(cap))` | the rest |
| `carryForward` not sent | all (WARN logged) | 0 |

- Kept days stay in `wfh_balance`; `carry_forward_days` is not used, because WFH deduction draws
  from `wfh_balance` only.
- Lapsed days get a `LAPSE` ledger row, reason e.g. `WFH type does not allow carry forward (before 2026-11)`.
- Rows of employees who joined in the period being credited are left alone. Rows of inactive or
  deleted employees are lapsed like any other. Duplicate rows are each closed separately.

Example — 12 days/year monthly, 3 days unused: `carryForward = false` → `LAPSE 3`, `CREDIT 1`,
balance 1. `carryForward = true`, cap 2 → `LAPSE 1`, `CREDIT 1`, balance 3.

### 7.3 Applying for WFH — `POST /employees/{employeeId}/wfh`

`WFHSeriveImpl.applyWFH`. The request is saved as `PENDING` with the `deductWfhBalance` flag the
caller sent, and an action item is created for the manager. **A WFH request has no WFH type, and the
balance is not checked at apply time.**

### 7.4 Approving — `PUT /employees/{employeeId}/wfh/{id}/status?status=APPROVED`

`WFHSeriveImpl.updateWFHStatus` → `WfhBalanceServiceImpl.deductWfhBalance` (transactional).
Only `APPROVED` (any case) triggers a deduction; any other status is just saved.

1. The request must currently be `PENDING` (exact, upper case), otherwise
   "WFH Tracker is not in PENDING status". This is what stops a second approval deducting again.
2. `deductWfhBalance = false` → status set to `APPROVED`, **no deduction**, done.
3. `days = end − start + 1` in **calendar days** (weekends included).
4. Load the employee's current-year active WFH rows, **sorted by WFH type name**.
5. The total across those rows must cover `days`, otherwise "Insufficient WFH balance".
6. Take from each row in order until `days` is covered. One `DEBIT` ledger row per balance touched,
   `reason = "WFH #<trackerId>"`.

Example — employee has `Adhoc = 2` and `Regular = 4`; a 3-day request is approved:

| Row (type-name order) | Taken | Balance after | Ledger |
|---|---|---|---|
| `Adhoc` | 2 | 0 | `DEBIT 2`, 2 → 0 |
| `Regular` | 1 | 3 | `DEBIT 1`, 4 → 3 |

With a single WFH type this is a plain debit from that one balance.

### 7.5 Refund — `POST /employee/wfh-balance/{employeeId}/disburse/{wfhTrackerId}`

`WfhBalanceServiceImpl.disburseWfhBalance`. Gives back the days an approved request deducted.

- Refused unless the request belongs to that employee, is `APPROVED`, and has `deductWfhBalance = true`.
- **Once per request:** a second call is refused. It is detected by the ledger row the first call
  wrote (`reason = "Refund WFH #<id>"`).
- The days (calendar days of the request) go to the employee's **first** current-year balance in
  type-name order, with a `CREDIT` ledger row.
- The request's status is **not** changed. Call the refund while the request is still `APPROVED`,
  then change its status.

### 7.6 Year end

There is no WFH rollover. On 1 Jan the scheduler finds no row for the new year and creates one at
the first period's credit. Last year's rows stay in the table but are no longer read. Unused WFH days
do not carry forward.

### 7.7 WFH ledger (`wfh_transactions`)

| Type | Written by | `days` | `reason` |
|---|---|---|---|
| `INITIALIZATION` | joining / new WFH type | whole days stored | `NEW_EMPLOYEE_INITIALIZATION` / `NEW_WFH_TYPE_INITIALIZATION` |
| `LAPSE` | scheduler (period boundary) | days forfeited | why it lapsed, e.g. `… (before 2026-Q4)` |
| `CREDIT` | scheduler | that period's whole days | e.g. `QUARTERLY disbursal 2026-Q4` |
| `DEBIT` | approval | days taken from that balance | `WFH #<trackerId>` |
| `CREDIT` | refund | days given back | `Refund WFH #<trackerId>` |

Reconciliation, per employee, type and year: `INITIALIZATION + CREDIT − DEBIT − LAPSE = closing balance`.

---

## 8. Leave vs WFH at a glance

| | Leave | WFH |
|---|---|---|
| Balance precision | Decimal | Whole days |
| Join credit | Prorata rounded to 0.25 | Same, then rounded to a whole day |
| Period credit | `totalDays / periods`, 2 decimals | Whole-day spread that sums to `totalDays` |
| Request carries a type | Yes | No |
| Balance check at apply | Yes | No |
| Days counted | Working days (Sat/Sun excluded) | Calendar days |
| Balance check at approval | Yes, under a row lock | Yes, no lock |
| Deducted from | The one row for that leave type; carry-forward first | All current-year WFH rows, in type-name order |
| Optional deduction | No, approval always deducts | Yes, `deductWfhBalance` flag |
| Status values | `Pending` / `Approved` (case-insensitive) | `PENDING` / `APPROVED` |
| Status change after a decision | Refused | Allowed (only re-approval is blocked) |
| Refund | None | `POST …/disburse/{wfhTrackerId}`, once per request |
| Year-end | Rollover with carry-forward / lapse | None; fresh row each year |
| Ledger type | Enum | Free string |

---

## 9. Endpoints

All take the tenant from the `X-Tenant-Id` header. None of them is authenticated.

| Purpose | Leave | WFH |
|---|---|---|
| Current-year balances | `GET /employee/leave-balance/{employeeId}` | `GET /employee/wfh-balance/{employeeId}` |
| Init for a new employee | `POST /employee/leave-balance/initialize/{employeeId}` | `POST /employee/wfh-balance/initialize/{employeeId}` |
| Init a new type for everyone | `POST /employee/leave-balance/initialize-for-new-leave-type` | `POST /employee/wfh-balance/initialize-for-new-wfh-type` |
| Manual disbursal (current period) | `POST /employee/leave-balance/disburse-{monthly,quarterly,half-yearly,yearly}-leave` | `POST /employee/wfh-balance/disburse-{monthly,quarterly,half-yearly,yearly}-wfh` |
| Year-end rollover | `POST /employee/leave-balance/rollover-leave-year` | — |
| Refund | — | `POST /employee/wfh-balance/{employeeId}/disburse/{wfhTrackerId}` |
| Apply | `POST /employees/{employeeId}/leave-tracker` | `POST /employees/{employeeId}/wfh` |
| Approve / reject | `PUT /employees/{employeeId}/leave-tracker/{id}/status?status=` | `PUT /employees/{employeeId}/wfh/{id}/status?status=` |

Company service calls made by this service:

| Call | Used by | Property |
|---|---|---|
| `GET /leave-types`, `GET /wfh-types` | init on joining | `company.service.base.url` |
| `GET /leave-types/schedule/{freq}`, `GET /wfh-types/schedule/{freq}` | scheduler, rollover | `company.service.url` |
| `GET /api/v1/tenants/databases` | tenant list for cron | `tenant.config.api.url` |

---

## 10. Code map

| Concern | File |
|---|---|
| Leave cron + periodic credit | `service/LeaveDisbursalSchedulerService.java` |
| WFH cron + periodic credit | `service/WFHDisbursalSchedulerService.java` |
| Leave init, deduction | `service/LeaveBalanceService.java` |
| Leave apply, approve | `service/LeaveTrackerServiceImpl.java` |
| Leave year-end rollover | `service/LeaveYearEndRolloverService.java` |
| WFH init, deduction, refund | `service/WfhBalanceServiceImpl.java` |
| WFH apply, approve | `service/WFHSeriveImpl.java` |
| Prorata | `utility/ProrataLeaveCalculator.java`, `utility/ProrataWfhCalculator.java` |
| Period key, period start, whole-day spread | `utility/DisbursalFrequency.java` |
| Who accrues | `utility/DisbursalEligibility.java` |
| Working-day count | `utility/WorkingDays.java` |
| Per-tenant cron loop | `utility/TenantJobRunner.java`, `utility/TenantRegistry.java` |
| Period claim | `repository/DisbursalRunRepository.java`, `dao/DisbursalRun.java` |
| Carry-forward-first deduction | `dao/EmployeeLeaveBalance.deductDays` |
| Row lock for approval | `repository/EmployeeLeaveBalanceRepository.findActiveForUpdate` |

Tests: `ProrataCalculatorTest`, `DisbursalEligibilityTest`, `WorkingDaysTest`,
`LeaveBalanceServiceTest`, `LeaveTrackerServiceImplTest`, `LeaveYearEndRolloverServiceTest`,
`WFHDisbursalSchedulerServiceTest`, `WfhBalanceServiceImplTest`.
