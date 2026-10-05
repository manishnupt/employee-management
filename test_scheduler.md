# Disbursal Scheduler — API Test Plan

How to test the leave / WFH disbursal scheduler and the leave year-end rollover by calling the
manual trigger endpoints. Branch: `feature/proratacalc`.

How the scheduler works is in [scheduler-flow.md](scheduler-flow.md); known gaps are in
[scheduler.md](scheduler.md). Test cases below reference those gaps where the expected result is a
known limitation rather than a bug.

---

## 1. What can and cannot be tested through the API

The manual endpoints call the same code as the cron jobs, for **one tenant** (from `X-Tenant-Id`)
and always for the period containing **today**.

| Covered by this plan | Not reachable through the API |
|---|---|
| Per-period credit amounts (leave and WFH) | The cron expressions themselves (when the jobs fire) |
| Idempotency and concurrent calls (`disbursal_run` claim) | `TenantJobRunner` looping over all tenants, and one tenant failing without stopping the rest |
| Eligibility (deleted, inactive, joined this period) | Tenant API (`tenant.config.api.url`) being down |
| Per-type transaction rollback and retry | Periods other than the current one, unless the server clock is changed |
| Leave year-end rollover (carry forward / lapse) | |
| Tenant isolation via `X-Tenant-Id` | |

Two things to keep in mind when reading results:

- **The disburse endpoints return `200` with the same success message whether they credited,
  skipped (already disbursed), found no types, or a type failed.** Per-type failures are caught and
  logged. Never assert on the response alone — always check the balance, the ledger and `disbursal_run`.
- Period keys depend on the day the tests run. The examples use a run date in **October 2026**:
  `2026-10`, `2026-Q4`, `2026-H2`, `2026`. Substitute the current values.

---

## 2. Setup

### 2.1 Environment

| Item | Value |
|---|---|
| Base URL | `http://localhost:9091` (no context path) |
| Tenant header | `X-Tenant-Id: <tenant>` on every call |
| Auth | None on the balance / disburse endpoints |
| Company service | `company.service.url` — owns the leave / WFH types the scheduler reads |
| DB | The tenant's Postgres database; DDL from [scheduler.md §2](scheduler.md#2-before-deploying--db-changes) must be applied |

```bash
export BASE=http://localhost:9091
export TENANT=<test-tenant>
export COMPANY=<company.service.url>
alias hc='curl -s -w "\nHTTP %{http_code}\n" -H "X-Tenant-Id: $TENANT"'
```

Use a **dedicated test tenant**. Disbursal credits every eligible employee in the tenant and there
is no undo endpoint.

### 2.2 Know the types under test

The scheduler credits whatever the Company service returns, so record it first:

```bash
for f in monthly quarterly half_yearly yearly; do
  echo "== $f"; hc "$COMPANY/leave-types/schedule/$f"; hc "$COMPANY/wfh-types/schedule/$f"
done
```

Note `name`, `totalDays`, `carryForward`, `maxCarryForwardDays` per type. Ideally the tenant has at
least one leave type and one WFH type per frequency, and for rollover one type with carry-forward
and a cap, one with carry-forward and no cap, and one without carry-forward.

For fully deterministic values, start the app with `--company.service.url=<stub>` pointing at a
stub (WireMock or similar) that serves fixed responses for the eight `/schedule/{freq}` paths. This
is also the only way to run the Company-service failure cases in section 7.

### 2.3 Test employees

Create via `POST /employee` or pick existing ones, then shape them with SQL. `created_at` cannot be
changed through the API, so backdating needs SQL.

| Id | Purpose | State |
|---|---|---|
| E1 | Normal accrual | `deleted = false`, `job_status = 'Active'`, `created_at` before 1 Jan of this year |
| E2 | Joined this month | `created_at` on or after the 1st of the current month |
| E3 | Joined this quarter, not this month | `created_at` inside the current quarter but before the current month (skip if today is in the quarter's first month) |
| E4 | Soft-deleted | `deleted = true`, old `created_at` |
| E5 | Inactive | `job_status = 'Resigned'`, old `created_at` |
| E6 | Blank status | `job_status = NULL`, old `created_at` |
| E7 | No balance row | Old `created_at`, active, **no** `employee_leave_balance` / `employee_wfh_balance` rows for this year |

```sql
UPDATE employee SET created_at = '2025-06-01' WHERE employee_id IN ('<E1>','<E4>','<E5>','<E6>','<E7>');
UPDATE employee SET deleted = true            WHERE employee_id = '<E4>';
UPDATE employee SET job_status = 'Resigned'   WHERE employee_id = '<E5>';
UPDATE employee SET job_status = NULL         WHERE employee_id = '<E6>';
DELETE FROM employee_leave_balance WHERE employee_id = '<E7>';
DELETE FROM employee_wfh_balance   WHERE employee_id = '<E7>';
```

### 2.4 Verification queries

```sql
-- V1: claims
SELECT kind, type_name, frequency, period_key, days_per_employee, created_at
FROM disbursal_run WHERE tenant_id = '<tenant>' ORDER BY created_at;

-- V2: leave balance and ledger for an employee
SELECT leave_type_name, year, leave_balance, carry_forward_days, remaining_days, is_active
FROM employee_leave_balance WHERE employee_id = '<id>' ORDER BY year, leave_type_name;

SELECT leave_type_name, transaction_type, days, year, balance_before, balance_after, reason, created_at
FROM leave_transactions WHERE employee_id = '<id>' ORDER BY created_at;

-- V3: WFH balance and ledger for an employee
SELECT wfh_type_name, year, wfh_balance, carry_forward_days, remaining_days, is_active
FROM employee_wfh_balance WHERE employee_id = '<id>' ORDER BY year, wfh_type_name;

SELECT wfh_type_name, transaction_type, days, year, balance_before, balance_after, reason, created_at
FROM wfh_transactions WHERE employee_id = '<id>' ORDER BY created_at;
```

Balances are also readable through the API (current year, active rows only):

```bash
hc "$BASE/employee/leave-balance/<id>"
hc "$BASE/employee/wfh-balance/<id>"
```

### 2.5 Re-running a period

A period can only be credited once. To repeat a test, remove its claim. A repeat run also re-applies
the period-boundary carry / lapse step (4.5) to the balance the previous run left, so work out the
expected value from the row's state just before the call.

```sql
DELETE FROM disbursal_run
WHERE tenant_id = '<tenant>' AND kind IN ('LEAVE','WFH') AND period_key IN ('2026-10','2026-Q4','2026-H2','2026');
```

Do not delete the `LEAVE_ROLLOVER` row unless you are running section 6.

---

## 3. Endpoints under test

| Endpoint | Period credited |
|---|---|
| `POST /employee/leave-balance/disburse-monthly-leave` | current month, e.g. `2026-10` |
| `POST /employee/leave-balance/disburse-quarterly-leave` | current quarter, e.g. `2026-Q4` |
| `POST /employee/leave-balance/disburse-half-yearly-leave` | current half, e.g. `2026-H2` |
| `POST /employee/leave-balance/disburse-yearly-leave` | current year, e.g. `2026` |
| `POST /employee/leave-balance/rollover-leave-year` | rollover into the current year |
| `POST /employee/wfh-balance/disburse-monthly-wfh` | current month |
| `POST /employee/wfh-balance/disburse-quarterly-wfh` | current quarter |
| `POST /employee/wfh-balance/disburse-half-yearly-wfh` | current half |
| `POST /employee/wfh-balance/disburse-yearly-wfh` | current year |
| `GET /employee/leave-balance/{employeeId}`, `GET /employee/wfh-balance/{employeeId}` | verification |

None takes a request body.

---

## 4. Leave disbursal

Expected credit per type: `round(totalDays / periodsPerYear, 2)`.

| `totalDays` | Monthly | Quarterly | Half-yearly | Yearly |
|---|---|---|---|---|
| 24 | 2.0 | 6.0 | 12.0 | 24.0 |
| 18 | 1.5 | 4.5 | 9.0 | 18.0 |
| 10 | 0.83 | 2.5 | 5.0 | 10.0 |

### 4.1 Happy path — run once per frequency

```bash
hc -X POST "$BASE/employee/leave-balance/disburse-monthly-leave"
hc -X POST "$BASE/employee/leave-balance/disburse-quarterly-leave"
hc -X POST "$BASE/employee/leave-balance/disburse-half-yearly-leave"
hc -X POST "$BASE/employee/leave-balance/disburse-yearly-leave"
```

| # | Case | Steps | Expected |
|---|---|---|---|
| L-01 | Monthly credit | Record E1's balance; call monthly | `200 "Leave disbursed successfully"`. For each monthly type: E1's `leave_balance` = `totalDays/12` (2 dp); `carry_forward_days` = what the type's carry-forward rule keeps of the earlier balance (4.5); `remaining_days` = the two added. |
| L-02 | Ledger row | After L-01, V2 ledger for E1 | One `CREDIT` per monthly type: `days` = credit, `year` = current, `balance_after − balance_before = days`, `reason = "MONTHLY disbursal 2026-10"`. A `LAPSE` row precedes it if anything was forfeited. |
| L-03 | Claim row | After L-01, V1 | One row per monthly type: `kind = LEAVE`, `frequency = MONTHLY`, `period_key = 2026-10`, `days_per_employee` = credit. |
| L-04 | Only that frequency | After L-01 | Quarterly / half-yearly / yearly types are **not** credited and have no claim row. |
| L-05 | Quarterly | Call quarterly | As L-01..03 with `totalDays/4`, `period_key = 2026-Q4`, reason `QUARTERLY disbursal 2026-Q4`. |
| L-06 | Half-yearly | Call half-yearly | `totalDays/2`, `2026-H2`, reason `HALF_YEARLY disbursal 2026-H2`. |
| L-07 | Yearly | Call yearly | `totalDays`, `2026`, reason `YEARLY disbursal 2026`. |
| L-08 | Fractional rounding | A type with `totalDays = 10`, monthly | Credit is `0.83` (known gap [5.1](scheduler.md#5-open-issues--leave): 9.96 over a year). |
| L-09 | Row created when missing | E7 (no balance row); call any frequency | A new row for E7: `year` = current, `is_active = true`, `leave_balance` = credit, `carry_forward_days = 0`. Ledger `balance_before = 0`. |
| L-10 | Rollover claim side effect | First leave disbursal of the year for the tenant; V1 | A `LEAVE_ROLLOVER` / `*` / `<year>` row also exists (rollover runs before every leave disbursal). |

### 4.2 Idempotency

| # | Case | Steps | Expected |
|---|---|---|---|
| L-11 | Repeat call | Call monthly again right after L-01 | `200`, same message. **No** balance change, no new ledger rows, no new claim rows. Log: `Skipping leaveName=… already disbursed`. |
| L-12 | Repeat ×5 | Loop the call 5 times | Same as L-11. |
| L-13 | Concurrent calls | Remove the monthly claims (2.5); record balances; fire 5 calls in parallel: `for i in 1 2 3 4 5; do hc -X POST "$BASE/employee/leave-balance/disburse-monthly-leave" & done; wait` | All `200`. Each employee credited **exactly once** per type; one claim row and one `CREDIT` per employee per type. |
| L-14 | Frequencies are independent | Call monthly, then quarterly | Quarterly types still credited; a monthly claim does not block `2026-Q4`. |
| L-15 | Re-credit after claim removed | Delete one type's claim row; call again | Only that type is credited again. Confirms the claim row is what gates the credit. |

### 4.3 Eligibility

Remove the claims (2.5), record balances for E1–E7, call all four leave endpoints, compare.

| # | Employee | Monthly | Quarterly | Half-yearly / Yearly |
|---|---|---|---|---|
| L-16 | E1 active, old | credited | credited | credited |
| L-17 | E2 joined this month | **skipped** | **skipped** | **skipped** (see note) |
| L-18 | E3 joined this quarter, before this month | credited | **skipped** | see note |
| L-19 | E4 soft-deleted | skipped | skipped | skipped |
| L-20 | E5 `Resigned` | skipped | skipped | skipped |
| L-21 | E6 null status | credited | credited | credited |
| L-22 | E7 no row | credited (row created) | credited | credited |

Note for L-17 / L-18: the rule is *skip when `created_at` date ≥ period start*. An employee who
joined in October 2026 is on or after 1 Oct (month and Q4 start), 1 Jul (H2 start) and 1 Jan (year
start), so they are skipped by **all four**. An employee who joined in, say, March is skipped only
by yearly. Work out the expected column per employee from their `created_at` and the four period
starts before running.

| # | Case | Steps | Expected |
|---|---|---|---|
| L-23 | Boundary: joined exactly on period start | `created_at = '<1st of month> 00:00:00'`; call monthly | Skipped. |
| L-24 | Boundary: joined the day before | `created_at = '<last day of previous month> 23:59:59'`; remove claim; call monthly | Credited. |
| L-25 | Status case / whitespace | `job_status = ' active '`; remove claim; call monthly | Credited. |
| L-26 | Skipped employees get no ledger row | After L-19 / L-20 | No `CREDIT` row and no new balance row for E4 / E5. |
| L-27 | Join + scheduler do not double-credit | Create a new employee today (init runs on create), then call monthly | Balance = prorated `INITIALIZATION` only; no `CREDIT` for this period. |

### 4.4 Failure and retry

| # | Case | Steps | Expected |
|---|---|---|---|
| L-28 | One type fails, others continue | Insert a duplicate active current-year `employee_leave_balance` row for E1 for **one** monthly type; remove claims; call monthly | `200` (failure is swallowed). That type: **no** claim row, **no** credit for anyone, ERROR log `Leave disbursal failed for leaveName=…`. Other monthly types: credited normally. |
| L-29 | Retry after fix | Delete the duplicate; call monthly again | The failed type is now credited once; the others are skipped. |
| L-30 | No types for a frequency | A frequency with no leave types in Company service | `200`, nothing changes, WARN `No … leave types found`. |

### 4.5 Period-boundary carry forward / lapse

Before crediting, the scheduler applies the type's carry-forward rule to what is left from earlier
periods. Stage E1's current-year row with SQL, remove the type's claim, call the endpoint for the
type's frequency. Examples use a monthly type with 24 days/year (credit 2.0).

```sql
UPDATE employee_leave_balance SET leave_balance = 1.5, carry_forward_days = 0, remaining_days = 1.5
WHERE employee_id = '<E1>' AND leave_type_name = '<type>' AND year = 2026 AND is_active;
```

| # | Case | Staged row (`leave_balance` / `carry_forward_days`) | Expected row after | Expected ledger |
|---|---|---|---|---|
| P-01 | `carryForward = false` | 1.5 / 0 | 2.0 / 0 | `LAPSE 1.5` (1.5→0, "Leave type does not allow carry forward (before 2026-10)"), then `CREDIT 2` (0→2) |
| P-02 | `carryForward = false`, days in both columns | 1.5 / 1 | 2.0 / 0 | `LAPSE 2.5` (2.5→0), `CREDIT 2` (0→2) |
| P-03 | `carryForward = true`, no cap | 1.5 / 1 | 2.0 / 2.5 | `CREDIT 2` (2.5→4.5) only |
| P-04 | `carryForward = true`, cap 1 | 2 / 1 | 2.0 / 1 | `LAPSE 2` (3→1, "Exceeds carry-forward cap of 1.0 day(s) (before 2026-10)"), `CREDIT 2` (1→3) |
| P-05 | Below the cap | 0.5 / 0, cap 1 | 2.0 / 0.5 | `CREDIT 2` (0.5→2.5) only |
| P-06 | Nothing left | 0 / 0 | 2.0 / 0 | `CREDIT 2` only |
| P-07 | Negative balance | −1 / 0 | 1.0 / 0 | `CREDIT 2` (−1→1) only; deficit is not written off |
| P-08 | Joined this period | E2 with a prorated row, `carryForward = false` | Unchanged | None (no `LAPSE`, no `CREDIT`) |
| P-09 | Inactive employee | E5 with 3 / 0, `carryForward = false` | 0 / 0 | `LAPSE 3` only, no `CREDIT` |
| P-10 | Deleted employee | E4 with 3 / 0, `carryForward = false` | 0 / 0 | `LAPSE 3` only |
| P-11 | Repeat call | Call again after P-01 | Unchanged | No second `LAPSE`; the fresh credit is not lapsed |
| P-12 | Other frequencies | Repeat P-01 and P-04 on a quarterly and a half-yearly type | Same rule at the quarter / half boundary | Reasons end `(before 2026-Q4)` / `(before 2026-H2)` |
| P-13 | Only the type being credited | Call monthly | Rows of quarterly / half-yearly / yearly types untouched | — |
| P-14 | Lapse rolls back with a failed credit | Stage P-01 and the duplicate row from L-28 on the same type | Row unchanged, no claim | No `LAPSE`, no `CREDIT` |
| P-15 | Carried days are spent first | After P-03, approve a 1-day leave | `carry_forward_days` 2.5 → 1.5, `leave_balance` stays 2.0 | `DEBIT 1` |
| P-16 | After the year-end rollover | Run R-01 for an employee whose 2026 row holds nothing but the 5 carried days, then the monthly disbursal | 2.0 / 5: the days carried by the rollover are within the cap and are not lapsed again | `CREDIT` only |

---

## 5. WFH disbursal

WFH credits are whole days, spread so the year sums to `totalDays`:

```
credit = round(totalDays × i / periods) − round(totalDays × (i − 1) / periods)     i = period number in the year
```

| `totalDays`, frequency | Per period, in order | Credit for an October run |
|---|---|---|
| 12, monthly | 1,1,1,1,1,1,1,1,1,1,1,1 | 1 |
| 10, monthly | 1,1,1,0,1,1,1,1,1,0,1,1 | **0** |
| 18, quarterly | 5,4,5,4 | 4 |
| 5, half-yearly | 3,2 | 2 |
| n, yearly | n | n |

Compute the expected credit for each type and the current period before running.

```bash
hc -X POST "$BASE/employee/wfh-balance/disburse-monthly-wfh"
hc -X POST "$BASE/employee/wfh-balance/disburse-quarterly-wfh"
hc -X POST "$BASE/employee/wfh-balance/disburse-half-yearly-wfh"
hc -X POST "$BASE/employee/wfh-balance/disburse-yearly-wfh"
```

| # | Case | Steps | Expected |
|---|---|---|---|
| W-01 | Monthly credit | Record E1; call monthly | `200 "Monthly WFH disbursed successfully"`. `wfh_balance` = (what the type's carry-forward rule keeps of the earlier balance, see 5.1) + the computed whole days; integer value. |
| W-02 | Ledger row | V3 ledger for E1 | `CREDIT`, `days` = credit, `year` = current, before/after differ by `days`, `reason = "MONTHLY disbursal 2026-10"`. |
| W-03 | Claim row | V1 | `kind = WFH`, `period_key = 2026-10`, `days_per_employee` = this period's credit. |
| W-04 | Quarterly / half-yearly / yearly | Call each | Same checks with `2026-Q4` / `2026-H2` / `2026`; messages `Quarterly…`, `Half-yearly…`, `Yearly WFH disbursed successfully`. |
| W-05 | Zero-credit period | A type whose credit works out to 0 this period (e.g. 10/yr monthly in April or October) | Claim row **is** written with `days_per_employee = 0`; **no** `CREDIT` row and **no** new balance row. The carry / lapse step (5.1) still runs. |
| W-06 | Row created when missing | E7; call a frequency with a non-zero credit | New row: `year` = current, `is_active = true`, `wfh_balance` = credit. |
| W-07 | Legacy `year = 0` rows ignored | Give E1 a `year = 0` row for the type | The current-year row is credited; the `year = 0` row is untouched. |
| W-08 | Repeat call | Call monthly twice | Second call changes nothing. Log `Skipping WFH type=… already disbursed`. |
| W-09 | Concurrent calls | Remove claims; 5 parallel calls | Credited exactly once. |
| W-10 | Eligibility | Repeat L-16..L-26 against the WFH endpoints | Same outcomes as leave. |
| W-11 | Duplicate balance rows | Insert a second active current-year row for E1 / one type; remove claim; call | Unlike leave, **does not fail**: the row with the lowest `id` is credited, the other is untouched, WARN `Duplicate WFH balance rows`. One ledger row. |
| W-12 | Leave and WFH are independent | Call monthly leave, then monthly WFH | Both credited; claims differ by `kind`. A WFH type and a leave type with the same name do not block each other. |
| W-13 | No rollover on WFH | First WFH disbursal of the year; V1 | No `LEAVE_ROLLOVER` row is created by WFH endpoints. |
| W-14 | No types for a frequency | Frequency with no WFH types | `200`, nothing changes, WARN `No … WFH types found`. |

### 5.1 Period-boundary carry forward / lapse

Same rule as leave (4.5), on `wfh_balance` in whole days. First confirm what the schedule response
sends for the type (2.2): `carryForward` and `maxCarryForwardDays`. Stage E1's current-year row,
remove the type's claim, call the endpoint. Examples use 12 days/year monthly (credit 1).

```sql
UPDATE employee_wfh_balance SET wfh_balance = 3, remaining_days = 3
WHERE employee_id = '<E1>' AND wfh_type_name = '<type>' AND year = 2026 AND is_active;
```

| # | Case | Staged `wfh_balance` | Expected after | Expected ledger |
|---|---|---|---|---|
| WP-01 | `carryForward = false` | 3 | 1 | `LAPSE 3` (3→0, "WFH type does not allow carry forward (before 2026-10)"), `CREDIT 1` (0→1) |
| WP-02 | `carryForward = true`, no cap | 3 | 4 | `CREDIT 1` (3→4) only |
| WP-03 | `carryForward = true`, cap 2 | 3 | 3 | `LAPSE 1` (3→2), `CREDIT 1` (2→3) |
| WP-04 | Fractional cap | 3, cap 1.5 | 2 | `LAPSE 2` (cap floored to 1), `CREDIT 1` |
| WP-05 | `carryForward` not sent | 3 | 4 | `CREDIT 1` only; WARN `has no carryForward setting` |
| WP-06 | Zero-credit period | 3, `carryForward = false`, credit works out to 0 | 0 | `LAPSE 3` only; claim row with `days_per_employee = 0` |
| WP-07 | Joined this period | E2 with a prorated row, `carryForward = false` | Unchanged | None |
| WP-08 | Inactive / deleted employee | E5 / E4 with 3, `carryForward = false` | 0 | `LAPSE 3` only |
| WP-09 | Duplicate rows | Two rows (2 and 1), `carryForward = false` | Oldest = credit, other = 0 | One `LAPSE` per row, one `CREDIT` |
| WP-10 | Repeat call | Call again after WP-01 | Unchanged | No second `LAPSE` |
| WP-11 | Kept days are usable | After WP-03, approve a 3-day WFH request with `deductWfhBalance = true` | 0 | `DEBIT 3` |
| WP-12 | Other frequencies | Repeat WP-01 on quarterly / half-yearly / yearly types | Same rule | Reasons end `(before 2026-Q4)` / `(before 2026-H2)` / `(before 2026)` |

---

## 6. Leave year-end rollover

The rollover runs once per tenant per year and is claimed in `disbursal_run`
(`kind = LEAVE_ROLLOVER`, `type_name = '*'`, `period_key = <current year>`). In a test tenant it has
usually already been claimed by the first leave disbursal, with nothing to roll. To test it, stage
prior-year rows and release the claim.

### 6.1 Staging

```sql
-- release the claim
DELETE FROM disbursal_run
WHERE tenant_id = '<tenant>' AND kind = 'LEAVE_ROLLOVER' AND period_key = '2026';

-- one prior-year row per case (adjust employee, type, days)
INSERT INTO employee_leave_balance
  (employee_id, leave_type_name, leave_balance, carry_forward_days, remaining_days, year, is_active, created_at, updated_at)
VALUES
  ('<E1>', '<type with cap 5>',      8,  0,  8, 2025, true, now(), now()),
  ('<E1>', '<type no cap>',          6,  1,  7, 2025, true, now(), now()),
  ('<E1>', '<type no carry fwd>',    4,  0,  4, 2025, true, now(), now()),
  ('<E1>', 'ZZ_DISCONTINUED',        3,  0,  3, 2025, true, now(), now()),
  ('<E4>', '<type with cap 5>',      5,  0,  5, 2025, true, now(), now()),   -- deleted employee
  ('<E5>', '<type with cap 5>',      2,  0,  2, 2025, true, now(), now()),   -- inactive, not deleted
  ('<E6>', '<type with cap 5>',     -2,  0, -2, 2025, true, now(), now());   -- negative close
```

Record each employee's current-year rows first, then:

```bash
hc -X POST "$BASE/employee/leave-balance/rollover-leave-year"     # 200 "Leave year rollover completed"
```

### 6.2 Cases

| # | Case | Staged close | Expected ledger (`year`) | Expected rows |
|---|---|---|---|---|
| R-01 | Carry-forward with cap | 8 days, cap 5 | `LAPSE 3` (2025, 8→5, "Exceeds carry-forward cap of 5.0 day(s)"); `CARRY_FORWARD_OUT 5` (2025, 5→0, "Carried forward to 2026"); `CARRY_FORWARD 5` (2026, "Carried forward from 2025") | 2025 row `is_active = false`. 2026 row `carry_forward_days` **+5**, `leave_balance` unchanged. |
| R-02 | Carry-forward, no cap | 7 days (6 + 1) | `CARRY_FORWARD_OUT 7`, `CARRY_FORWARD 7`; no `LAPSE` | 2026 `carry_forward_days` +7. |
| R-03 | Below the cap | 3 days, cap 5 | `CARRY_FORWARD_OUT 3`, `CARRY_FORWARD 3`; no `LAPSE` | 2026 `carry_forward_days` +3. |
| R-04 | Type without carry-forward | 4 days | `LAPSE 4` ("Leave type does not allow carry forward") | 2025 row inactive. 2026 row exists (created at 0 if it was missing), no carry. |
| R-05 | Discontinued type | 3 days of `ZZ_DISCONTINUED` | `LAPSE 3` ("Leave type discontinued") | 2025 row inactive. **No** 2026 row for that type. |
| R-06 | Deleted employee | E4, 5 days | `LAPSE 5` ("Employee deleted or not found") | 2025 row inactive. No 2026 row created. |
| R-07 | Inactive, not deleted | E5, 2 days | Carried as for an active employee | 2026 row with carry. Known gap [5.7](scheduler.md#5-open-issues--leave). |
| R-08 | Negative closing balance | E6, −2 | No ledger rows; WARN `closes negative` | 2025 row inactive; 2026 row not reduced (deficit not carried). |
| R-09 | Existing 2026 row | E1 already has a 2026 row | — | Carry is **added** to the existing row; no second 2026 row. |
| R-10 | No 2026 row yet | Employee with a 2025 row only | — | 2026 row created: `leave_balance = 0`, `carry_forward_days` = carried. |
| R-11 | Claim written | V1 after the call | — | `LEAVE_ROLLOVER`, `*`, `YEARLY`, `2026`, `days_per_employee = 0`. |
| R-12 | Repeat call | Call rollover again | No new ledger rows | No change. |
| R-13 | Nothing to roll | Release the claim with no prior-year active rows; call | None | Claim row is still written; no balances change. |
| R-14 | Rows older than last year | Stage a `year = 2024` active row | Rolled the same way, reasons reference 2024 | Closed; carry lands in 2026. |
| R-15 | Rollover via a disbursal | Release the claim, stage rows, call `disburse-monthly-leave` instead (also remove that month's `LEAVE` claims) | Rollover ledger rows, then any period-boundary `LAPSE` (4.5), then `CREDIT` rows | 2026 row: `leave_balance` = period credit; `carry_forward_days` = what the type's rule keeps of (earlier 2026 balance + days carried by the rollover). |
| R-16 | Duplicate 2026 rows block rollover | Two active 2026 rows for the same employee and type, with at least one prior-year row staged; release claim; call rollover | — | Non-2xx response; **no** claim row, no rows closed, no ledger rows (all rolled back). `disburse-*-leave` for the tenant also fails until the duplicate is removed. |
| R-17 | Recovery | Remove the duplicate; call again | As R-01.. | Rollover completes. |
| R-18 | Ledger reconciles | For each closed row | `INITIALIZATION + CREDIT + CARRY_FORWARD − DEBIT − LAPSE − CARRY_FORWARD_OUT` for 2025 = 0 after rollover (for rows whose history is complete) | — |
| R-19 | Carried days are spent first | After R-01, apply and approve a leave on that type (`POST /employees/{id}/leave-tracker`, `PUT …/{leaveId}/status?status=Approved`) | `DEBIT` | `carry_forward_days` drops before `leave_balance`. Apply needs an `Authorization` JWT whose user id equals `employeeId`. |

---

## 7. Tenant handling and dependencies

| # | Case | Steps | Expected |
|---|---|---|---|
| T-01 | Tenant isolation | Call monthly leave with `X-Tenant-Id: A` | Only tenant A's database changes; claim rows carry `tenant_id = A`. Tenant B's balances and `disbursal_run` are untouched. |
| T-02 | Second tenant still credits | Then call with `X-Tenant-Id: B` | B is credited; A's claim does not block B. |
| T-03 | Missing header | Call without `X-Tenant-Id` | Falls back to `defaultTenant` (`tomato`): its types are fetched and its database is credited. Confirm this is acceptable for an unauthenticated endpoint ([4.1](scheduler.md#4-open-issues--scheduler-and-platform)). |
| T-04 | Unknown tenant | `X-Tenant-Id: does-not-exist` | Non-2xx or no types; no rows written in any real tenant's database. |
| T-05 | `disbursal_run` table missing | A tenant DB without the DDL | Leave endpoints: non-2xx (rollover check fails). WFH endpoints: `200` but nothing credited, ERROR per type. |

With `company.service.url` pointed at a stub (2.2):

| # | Case | Stub behaviour | Expected |
|---|---|---|---|
| T-06 | Company service down — WFH | `500` or connection refused on `/wfh-types/schedule/monthly` | Non-2xx; no claim, no credit. A later call with the service up credits normally. |
| T-07 | Company service down — leave | Same on `/leave-types/schedule/*` | Non-2xx; no claim, no credit, **no rollover**. |
| T-08 | Rollover outage guard | All four `/leave-types/schedule/*` return `[]`, prior-year rows staged, claim released | Non-2xx (`No leave types returned … refusing to roll over`). Nothing lapsed, no claim row. |
| T-09 | Empty list for one frequency | `[]` for monthly only, rollover already claimed | `200`, WARN, nothing credited. |
| T-10 | WFH type without a name | An entry with neither `wfhType` nor `name` | That entry skipped with an ERROR log; other types credited. |
| T-11 | WFH name in `name` field | Entry uses `"name"` instead of `"wfhType"` | Credited under that name. |
| T-12 | `totalDays` changed after the claim | Disburse, change `totalDays` in the stub, call again | No second credit; the period keeps the amount first claimed. |
| T-13 | Company service receives the tenant | Inspect stub requests | Every call carries `X-Tenant-Id` = the request's tenant. |

---

## 8. Regression sweep

Run after the cases above, on E1 and one other employee.

| # | Check | Expected |
|---|---|---|
| G-01 | `remaining_days = leave_balance + carry_forward_days` on every touched leave row | Holds. |
| G-02 | `remaining_days = wfh_balance + carry_forward_days` on every touched WFH row | Holds. |
| G-03 | One `CREDIT` ledger row per (employee, type, period) | `SELECT employee_id, leave_type_name, reason, count(*) FROM leave_transactions WHERE transaction_type = 'CREDIT' GROUP BY 1,2,3 HAVING count(*) > 1;` returns nothing for periods you did not deliberately re-run. Same for `wfh_transactions`. |
| G-04 | One claim per (tenant, kind, type, period) | `SELECT tenant_id, kind, type_name, period_key, count(*) FROM disbursal_run GROUP BY 1,2,3,4 HAVING count(*) > 1;` returns nothing. |
| G-05 | No more than one active row per employee, type and year | `SELECT employee_id, leave_type_name, year, count(*) FROM employee_leave_balance WHERE is_active GROUP BY 1,2,3 HAVING count(*) > 1;` returns only the duplicates you staged and removed. Same for WFH. |
| G-06 | `GET` balances match the DB | API values equal the current-year active rows. |
| G-07 | No prior-year row is active after rollover | `SELECT count(*) FROM employee_leave_balance WHERE is_active AND year < <current year>;` is 0. |

---

## 9. Notes found while writing this plan

- [scheduler-flow.md §9](scheduler-flow.md#9-endpoints) lists
  `POST /employee/leave-balance/initialize-for-new-leave-type`, but `LeaveBalanceController` has no
  such mapping (only the WFH equivalent exists). Calls to it will 404.
- Because the disburse endpoints swallow per-type failures and still return `200`, an automated
  suite needs DB or log access; HTTP status alone cannot distinguish credited, skipped and failed.
- Periods other than the current one, and the real 1 Jan sequence (rollover first, then four jobs),
  can only be exercised end to end by running the app with a changed system date.
