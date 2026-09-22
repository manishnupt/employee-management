# Timesheet Workflow

Three endpoints exist for recording a timesheet entry: two original ones with narrow, distinct contracts, and a third, unified one that infers intent from the request shape. All three write to the same `Timesheet` row (one row per employee per `workDate`).

> **Key distinction:** `/clock` is for a user punching in/out *live* (clock-in now, clock-out later). `POST /timesheets` is for *manually* typing in a clock-in and clock-out time (e.g. backfilling a day, correcting a mistake, or entering a full day's hours at once).

---

## 1. `PUT /employees/{employeeId}/timesheets/clock` — Clock In / Clock Out

**Controller:** `TimesheetController.clockInOut` → **Service:** `TimesheetServiceImpl.clock`

Used when an employee is actively clocking in or out in real time. It is a two-step, stateful flow against a single day's record:

1. **Clock-in (first call of the day)**
   - Looks up a `Timesheet` for `(employeeId, workDate)`.
   - If none exists, creates a new one with `clockIn` set from the request, `clockOut` left null, and `status = "CLOCK_OUT_PENDING"`.

2. **Clock-out (second call of the day)**
   - Looks up the same `(employeeId, workDate)` record.
   - If it doesn't exist, or `clockIn` is null → throws `"No clock-in record found for this date."`
   - If `clockOut` is already set → throws `"Already clocked out for this date."` (prevents double clock-out).
   - Otherwise sets `clockOut`, computes `totalHours` as the duration between `clockIn` and `clockOut`, and sets `status = "PENDING"`.

3. **Action item creation**
   - Once `clockOut` is set (i.e. the day is complete), an action item is created via `actionItemService.createActionItem(...)` and linked back to the timesheet (`linkedActionItemId`).

**Request body:** only needs `workDate` + `clockIn` (first call) or `workDate` + `clockOut` (second call).

**Response:** `201 CREATED` with the resulting `TimesheetDto`.

---

## 2. `POST /employees/{employeeId}/timesheets` — Manual Timesheet Entry

**Controller:** `TimesheetController.addTimesheet` → **Service:** `TimesheetServiceImpl.logWork`

Used when an employee (or someone on their behalf) manually types in both the clock-in and clock-out times for a day, rather than clocking in/out live. It's a straight upsert — no in/out state machine, no "already clocked out" guard:

1. Looks up a `Timesheet` for `(employeeId, workDate)`.
2. If it exists → overwrites `clockIn` and `clockOut` with whatever was submitted, and resets `status = "PENDING"`.
3. If it doesn't exist → creates a new record with the submitted `clockIn`/`clockOut`, `status = "PENDING"`.
4. If the employee has an `assignedManagerId` **and** `clockOut` is non-null, creates and links an action item — same as the `/clock` flow.

**Request body:** `workDate`, `clockIn`, `clockOut` (both times typically provided together since it's a manual entry).

**Response:** `201 CREATED` with the resulting `TimesheetDto`.

---

## Key Differences

| | `PUT /clock` | `POST /timesheets` |
|---|---|---|
| Purpose | Live punch in/out | Manual entry / correction of both times |
| Calls needed | 2 (clock-in, then clock-out) | 1 (both times at once) |
| Guards | Errors if no clock-in exists, or if already clocked out | None — always overwrites clock-in/out for that date |
| Status set | `CLOCK_OUT_PENDING` → `PENDING` | `PENDING` (always) |
| `totalHours` | Computed as `Duration.between(clockIn, clockOut)` in service, stored on entity | Not set directly in service; DTO's `totalHours` is derived via `TimesheetUtil.calculateWorkedTimeInWords` in `convertToDto` |
| Action item | Created when `clockOut` becomes non-null | Created when `clockOut` is non-null **and** employee has an assigned manager |

Both endpoints ultimately share the same underlying record (unique per `employeeId` + `workDate`), so a day started via `/clock` can later be corrected via `POST /timesheets`, and vice versa — the last write wins.

---

## 3. `PUT /employees/{employeeId}/timesheets/entry` — Unified Endpoint (implemented)

Both flows above read/write the exact same `(employeeId, workDate)` row, and the only real difference is *how much of the payload is provided and whether state guards should apply*. This third endpoint implements that as a single new API, alongside (not replacing) `/clock` and `POST /timesheets` — the request shape alone signals intent, without a separate `mode` flag.

**Controller:** `TimesheetController.recordTimesheetEntry` → **Service:** `TimesheetServiceImpl.recordTimesheetEntry`

**Endpoint:** `PUT /employees/{employeeId}/timesheets/entry`

### API Contract

**Path parameters**

| Param | Type | Required | Notes |
|---|---|---|---|
| `employeeId` | `string` | yes | Must exist in `Employee` table, else `404`-shaped error below |

**Headers**

| Header | Required | Notes |
|---|---|---|
| `Content-Type: application/json` | yes | |
| `X-Tenant-Id` | yes (per app convention) | Read by `TenantFilter` on every request to route to the correct tenant DB; not specific to this endpoint, but the DB lookups will hit the wrong/no tenant schema if omitted |

**Request body** (`TimesheetDto`)

| Field | Type | Format | Required | Notes |
|---|---|---|---|---|
| `workDate` | `string` | `yyyy-MM-dd` | yes | Identifies which day's record to create/update |
| `clockIn` | `string` | `HH:mm` | conditionally | At least one of `clockIn` / `clockOut` must be present |
| `clockOut` | `string` | `HH:mm` | conditionally | At least one of `clockIn` / `clockOut` must be present |

Any other field on `TimesheetDto` (`timesheetId`, `employeeId`, `totalHours`) is ignored if sent — `employeeId` comes from the path, the rest are server-computed.

Example — manual entry (both fields):
```json
{ "workDate": "2026-09-22", "clockIn": "09:00", "clockOut": "18:00" }
```
Example — clock-in punch (one field):
```json
{ "workDate": "2026-09-22", "clockIn": "09:05" }
```
Example — clock-out punch (one field):
```json
{ "workDate": "2026-09-22", "clockOut": "18:10" }
```

**Success response:** `201 CREATED`, body is `TimesheetDto`:
```json
{
  "timesheetId": 42,
  "employeeId": "E123",
  "workDate": "2026-09-22",
  "clockIn": "09:00",
  "clockOut": "18:00",
  "totalHours": "9h 0m"
}
```
`totalHours` is only populated when both `clockIn` and `clockOut` are set on the saved record (computed by `TimesheetUtil.calculateWorkedTimeInWords` in `convertToDto`); on a bare clock-in response it will be `null`.

**Error responses**

All three failure conditions are thrown as plain `RuntimeException`, the same as `/clock` and `POST /timesheets` already do. `GlobalExceptionHandler` only catches `BusinessException`, not `RuntimeException`, so these are **not** mapped to a clean `400` — they fall through to Spring Boot's default error handler as `500 INTERNAL_SERVER_ERROR`:
```json
{ "timestamp": "...", "status": 500, "error": "Internal Server Error", "path": "/employees/E123/timesheets/entry" }
```
(the exception message itself is logged server-side but not included in the response body by default).

| Condition | Message | Trigger |
|---|---|---|
| Employee doesn't exist | `Employee not found` | `employeeId` not in `Employee` table |
| Neither field sent | `At least one of clockIn or clockOut must be provided.` | both `clockIn` and `clockOut` are `null` |
| Clock-out with no prior clock-in | `No clock-in record found for this date.` | only `clockOut` sent, but no record (or no `clockIn`) exists for `(employeeId, workDate)` |
| Double clock-out | `Already clocked out for this date.` | only `clockOut` sent, but `clockOut` is already set for that date |

This 500-for-business-errors behavior is a pre-existing pattern shared by `/clock` and `POST /timesheets`, not something new introduced by `/entry` — flagging it here since it affects how a caller should handle failures (status code alone won't distinguish "bad request" from "server bug"; callers currently need to inspect logs or move these to `BusinessException` if cleaner 4xx responses are wanted).

### Decision logic (as implemented)

```
lookup existing Timesheet for (employeeId, workDate)

if clockIn == null AND clockOut == null:
    throw "At least one of clockIn or clockOut must be provided."

if clockIn != null AND clockOut != null:
    # MANUAL ENTRY — both times supplied at once
    upsert record: set clockIn, clockOut
    totalHours = Duration.between(clockIn, clockOut)
    status = "PENDING"
    (no "already clocked out" guard — an explicit manual entry always overwrites)

else if clockIn != null:
    # CLOCK-IN punch (or a partial edit of clockIn only)
    if no existing record:
        create record: clockIn = request.clockIn, status = "CLOCK_OUT_PENDING"
    else:
        update record: clockIn = request.clockIn   # do NOT touch existing clockOut
        if record.clockOut == null: status = "CLOCK_OUT_PENDING"

else:
    # CLOCK-OUT punch
    if no existing record OR existing.clockIn == null:
        throw "No clock-in record found for this date."
    if existing.clockOut != null:
        throw "Already clocked out for this date."
    update record: clockOut = request.clockOut
    totalHours = Duration.between(existing.clockIn, request.clockOut)
    status = "PENDING"

save record

if record.clockOut != null:
    actionItemService.createActionItem(employeeId, record, employee.getAssignedManagerId())
    # createActionItem itself no-ops (returns null) when assignedManagerId is null/empty,
    # so this naturally matches POST /timesheets' behavior without an extra explicit check
    if actionItemId != null: link it and re-save

return TimesheetDto
```

**Response:** `201 CREATED` with the resulting `TimesheetDto` (same convention as the other two endpoints).

### How each existing call maps onto the new endpoint

| Existing call | Equivalent call to `/entry` |
|---|---|
| `PUT /clock` with only `clockIn` | Same payload — hits the "clock-in punch" branch |
| `PUT /clock` with only `clockOut` | Same payload — hits the "clock-out punch" branch, guards still apply |
| `POST /timesheets` with `clockIn` + `clockOut` | Same payload — hits the "manual entry" branch, no guards, straight overwrite |

`/clock` and `POST /timesheets` are unchanged and still work exactly as documented above — `/entry` is additive, not a replacement.

### Why field presence is enough to infer intent

- **Both fields present** is unambiguous: nobody "clocks in and out" in the same instant — that shape only makes sense as a manual/backfilled entry, so it's safe to skip the stateful guards.
- **One field present** is unambiguous too: it's a single punch (in or out), so the same guard rules as `/clock` (no clock-in found / already clocked out) apply to protect against double punches.
- **Neither field present** is rejected outright — there's nothing to infer.

### Behavioral difference vs. `POST /timesheets`

`POST /timesheets` **always overwrites both** `clockIn` and `clockOut` from the request, even if only one was intended to change (sending `clockIn` with `clockOut` omitted would null out an existing clock-out, since Jackson leaves the DTO field `null` and `logWork` copies it verbatim). `/entry`'s clock-in-only branch fixes that: it updates `clockIn` and leaves any existing `clockOut` untouched.

### Trade-off

The contract is now implicit — callers must know that "send one field" vs. "send both fields" changes which validation path runs. That's fine for a frontend that already builds both request shapes, but it's less discoverable for new consumers unless they read this doc or the DTO gains an explicit `entryMode: "CLOCK" | "MANUAL"` field to make intent explicit rather than inferred.
