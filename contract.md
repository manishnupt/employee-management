# API Contract — Delete WFH, Delete Leave, Regularization

Contract for the `employee-management` service (Spring Boot, default port `9091`, no context path)
covering:

- [Delete WFH request](#2-delete-wfh-request) — `WFHTrackerController`
- [Delete leave request](#3-delete-leave-request) — `LeaveTrackerController`
- [Regularization APIs](#4-regularization-apis) — every endpoint of `RegularizationController`

| # | Method | Path | Success |
|---|---|---|---|
| 2 | `DELETE` | `/employees/{employeeId}/wfh/{id}` | `204 No Content` |
| 3 | `DELETE` | `/employees/{employeeId}/leave-tracker/{id}` | `204 No Content` |
| 4.1 | `POST` | `/employees/{employeeId}/regularizations` | `201 Created` |
| 4.2 | `GET` | `/employees/{employeeId}/regularizations` | `200 OK` |
| 4.3 | `GET` | `/employees/{employeeId}/regularizations/{id}` | `200 OK` |
| 4.4 | `PUT` | `/employees/{employeeId}/regularizations/{id}/status` | `200 OK` |
| 4.5 | `DELETE` | `/employees/{employeeId}/regularizations/{id}` | `204 No Content` |

---

## 1. Common conventions

### 1.1 Authorization header

Endpoints marked **Auth: required** read the `Authorization` header and compare the JWT `sub` claim
(the Keycloak user id) with the `{employeeId}` path variable. They must be equal — an employee can
only act on their own requests.

```
Authorization: Bearer <jwt>
```

- The `Bearer ` prefix is optional; a bare token is also accepted.
- The signature is **not** verified by this service; verification is expected upstream.

### 1.2 Error response

Every business failure — validation, not found, ownership mismatch, bad token — is returned as
**`400 Bad Request`** with this body. There are no `401`, `403` or `404` responses from these
endpoints.

```json
{
  "status": "FAILURE",
  "message": "Regularization not found",
  "timestamp": 1791446400000
}
```

| Field | Type | Notes |
|---|---|---|
| `status` | string | Always `"FAILURE"` |
| `message` | string | Human-readable reason; the exact strings are listed per endpoint |
| `timestamp` | number | Epoch milliseconds |

Token errors common to all **Auth: required** endpoints:

| Condition | `message` |
|---|---|
| Header present but blank | `Authorization token is missing.` |
| Token is not a decodable JWT | `Authorization token is malformed.` |
| Token has no `sub` claim | `Authorization token does not contain a user id.` |

Failures raised by the framework rather than the service do **not** use the body above; they return
Spring Boot's default error JSON (`timestamp`, `status`, `error`, `path`):

| Condition | Status |
|---|---|
| `Authorization` header absent on an **Auth: required** endpoint | `400` |
| `{id}` is not a number | `400` |
| Required query parameter missing | `400` |
| Request body is not valid JSON, or a date/time field is in the wrong format | `400` |
| Call to the Utility service (action items) fails | `500` |

### 1.3 Data formats

| Type | Format | Example |
|---|---|---|
| Date | `yyyy-MM-dd` | `2026-10-07` |
| Time | `HH:mm` (24-hour) | `09:30` |
| `employeeId` | string (Keycloak user id) | `3f6c1c1e-8a0b-4d7e-9b7a-2f1d5c0e9a11` |
| `id` | integer (long) | `42` |

---

## 2. Delete WFH request

Withdraws a WFH request that is still awaiting a decision.

```
DELETE /employees/{employeeId}/wfh/{id}
```

**Auth: required** (token `sub` must equal `employeeId`)

| Parameter | In | Type | Required | Description |
|---|---|---|---|---|
| `employeeId` | path | string | yes | Owner of the WFH request |
| `id` | path | long | yes | WFH request id |
| `Authorization` | header | string | yes | See [1.1](#11-authorization-header) |

Request body: none.

### Success — `204 No Content`

Empty body. The WFH request row is deleted and the manager's linked action item (if any) is removed
from the Utility service.

### Errors — `400 Bad Request`

| Condition | `message` |
|---|---|
| Token `sub` ≠ `employeeId` | `WFH requests can only be deleted for your own employee id.` |
| No WFH request with this `id` | `WFH request not found` |
| Request belongs to another employee | `WFH request does not belong to the specified employee` |
| Status is not pending | `WFH request is already {status} and cannot be deleted. Only a pending WFH request can be deleted.` |

Plus the token errors in [1.2](#12-error-response).

### Example

```bash
curl -X DELETE 'http://localhost:9091/employees/3f6c1c1e-8a0b-4d7e-9b7a-2f1d5c0e9a11/wfh/42' \
  -H 'Authorization: Bearer <jwt>'
```

---

## 3. Delete leave request

Withdraws a leave request that is still awaiting a decision.

```
DELETE /employees/{employeeId}/leave-tracker/{id}
```

**Auth: required** (token `sub` must equal `employeeId`)

| Parameter | In | Type | Required | Description |
|---|---|---|---|---|
| `employeeId` | path | string | yes | Owner of the leave request |
| `id` | path | long | yes | Leave request id |
| `Authorization` | header | string | yes | See [1.1](#11-authorization-header) |

Request body: none.

### Success — `204 No Content`

Empty body. The leave request row is deleted and the manager's linked action item (if any) is
removed from the Utility service. Leave balance is unchanged — balance is only deducted on
approval, and an approved leave cannot be deleted.

### Errors — `400 Bad Request`

| Condition | `message` |
|---|---|
| Token `sub` ≠ `employeeId` | `Leave requests can only be deleted for your own employee id.` |
| No leave request with this `id` | `Leave not found` |
| Request belongs to another employee | `Leave does not belong to the specified employee` |
| Status is not pending | `Leave request is already {status} and cannot be deleted. Only a pending leave request can be deleted.` |

Plus the token errors in [1.2](#12-error-response).

### Example

```bash
curl -X DELETE 'http://localhost:9091/employees/3f6c1c1e-8a0b-4d7e-9b7a-2f1d5c0e9a11/leave-tracker/17' \
  -H 'Authorization: Bearer <jwt>'
```

---

## 4. Regularization APIs

Base path: `/employees/{employeeId}/regularizations`

A regularization lets an employee supply clock-in/clock-out times for a past day whose timesheet
was missed. When a manager approves it, the times are written to that day's timesheet.

### Regularization object

Returned by every regularization endpoint that has a response body.

```json
{
  "regularizationId": 42,
  "employeeId": "3f6c1c1e-8a0b-4d7e-9b7a-2f1d5c0e9a11",
  "workDate": "2026-10-07",
  "clockIn": "09:30",
  "clockOut": "18:30",
  "reason": "Forgot to clock in",
  "status": "PENDING"
}
```

| Field | Type | Notes |
|---|---|---|
| `regularizationId` | long | Server-generated |
| `employeeId` | string | Owner |
| `workDate` | date `yyyy-MM-dd` | Day being regularized |
| `clockIn` | time `HH:mm` | |
| `clockOut` | time `HH:mm` | |
| `reason` | string \| null | Free text |
| `status` | string | `PENDING`, `APPROVED` or `REJECTED` |

Status lifecycle: `PENDING` → `APPROVED` or `PENDING` → `REJECTED`. Both end states are final.

---

### 4.1 Raise a regularization

```
POST /employees/{employeeId}/regularizations
```

**Auth: required** (token `sub` must equal `employeeId`)

| Parameter | In | Type | Required | Description |
|---|---|---|---|---|
| `employeeId` | path | string | yes | Employee raising the request |
| `Authorization` | header | string | yes | See [1.1](#11-authorization-header) |

#### Request body

```json
{
  "workDate": "2026-10-07",
  "clockIn": "09:30",
  "clockOut": "18:30",
  "reason": "Forgot to clock in"
}
```

| Field | Type | Required | Rules |
|---|---|---|---|
| `workDate` | date `yyyy-MM-dd` | yes | A past day (before today, IST) within the current month, not before the employee's onboarding date |
| `clockIn` | time `HH:mm` | yes | |
| `clockOut` | time `HH:mm` | yes | Must be after `clockIn` |
| `reason` | string | no | Not validated |

`regularizationId`, `employeeId` and `status` are ignored if sent.

#### Success — `201 Created`

Body: the created [Regularization object](#regularization-object) with `status: "PENDING"`.

Side effect: an action item is created for the employee's assigned manager in the Utility service.

#### Errors — `400 Bad Request`

Validations run in this order; the first failure is returned.

| Condition | `message` |
|---|---|
| Token `sub` ≠ `employeeId` | `Regularization can only be raised for your own employee id.` |
| Employee does not exist | `Employee not found` |
| `clockIn` or `clockOut` missing | `Both clockIn and clockOut are required to raise a regularization.` |
| `clockOut` not after `clockIn` | `Clock-out time must be after the clock-in time.` |
| `workDate` missing | `Work date is required to raise a regularization.` |
| `workDate` is today or in the future | `Regularization can only be raised for past days; received work date {workDate} (today is {today} IST).` |
| `workDate` is before the 1st of the current month | `Regularization can only be raised for days within the current month; received work date {workDate}.` |
| `workDate` is before onboarding | `Cannot raise a regularization for {workDate}. Employee was onboarded on {onboardingDate}.` |
| A pending or approved leave covers `workDate` | `Cannot raise a regularization for {workDate}. Leave from {startDate} to {endDate} is {status}.` |
| A pending regularization already exists for `workDate` | `A regularization request for {workDate} is already pending approval.` |
| The day already has a clocked-out timesheet | `Cannot raise a regularization for {workDate}. A timesheet is already filled for this day.` |

Plus the token errors in [1.2](#12-error-response).

Notes:
- A pending or approved **WFH** on `workDate` does not block a regularization.
- A day with no timesheet, or a timesheet with no clock-out, counts as missed and can be regularized.

#### Example

```bash
curl -X POST 'http://localhost:9091/employees/3f6c1c1e-8a0b-4d7e-9b7a-2f1d5c0e9a11/regularizations' \
  -H 'Authorization: Bearer <jwt>' \
  -H 'Content-Type: application/json' \
  -d '{"workDate":"2026-10-07","clockIn":"09:30","clockOut":"18:30","reason":"Forgot to clock in"}'
```

---

### 4.2 Get regularization history

```
GET /employees/{employeeId}/regularizations
```

**Auth: not checked by this endpoint**

| Parameter | In | Type | Required | Description |
|---|---|---|---|---|
| `employeeId` | path | string | yes | Employee whose requests are listed |

#### Success — `200 OK`

Array of [Regularization objects](#regularization-object) in all statuses. Returns `[]` when the
employee has none or does not exist. No pagination or filtering.

```json
[
  {
    "regularizationId": 42,
    "employeeId": "3f6c1c1e-8a0b-4d7e-9b7a-2f1d5c0e9a11",
    "workDate": "2026-10-07",
    "clockIn": "09:30",
    "clockOut": "18:30",
    "reason": "Forgot to clock in",
    "status": "PENDING"
  }
]
```

---

### 4.3 Get regularization by id

```
GET /employees/{employeeId}/regularizations/{id}
```

**Auth: not checked by this endpoint**

| Parameter | In | Type | Required | Description |
|---|---|---|---|---|
| `employeeId` | path | string | yes | Owner of the regularization |
| `id` | path | long | yes | Regularization id |

#### Success — `200 OK`

Body: the [Regularization object](#regularization-object).

#### Errors — `400 Bad Request`

| Condition | `message` |
|---|---|
| No regularization with this `id` | `Regularization not found` |
| It belongs to another employee | `Regularization does not belong to the specified employee` |

---

### 4.4 Approve or reject a regularization

```
PUT /employees/{employeeId}/regularizations/{id}/status?status={status}
```

**Auth: not checked by this endpoint**

| Parameter | In | Type | Required | Description |
|---|---|---|---|---|
| `employeeId` | path | string | yes | Owner of the regularization (not the approver) |
| `id` | path | long | yes | Regularization id |
| `status` | query | string | yes | `APPROVED` or `REJECTED`; case-insensitive, surrounding whitespace trimmed |

Request body: none.

#### Success — `200 OK`

Body: the [Regularization object](#regularization-object) with the new `status`.

Behaviour:
- **`APPROVED`** — the day's timesheet is created, or the existing row without a clock-out is
  completed, with the regularized `clockIn`/`clockOut`, computed `totalHours`, `status: "APPROVED"`
  and `isRegularised: true`.
- **`REJECTED`** — only the status is recorded; no timesheet is touched.
- **Idempotent repeat** — sending the status the regularization already has returns `200` with the
  unchanged object and no side effects.

#### Errors — `400 Bad Request`

| Condition | `message` |
|---|---|
| No regularization with this `id` | `Regularization not found` |
| It belongs to another employee | `Regularization does not belong to the specified employee` |
| `status` is not `APPROVED` or `REJECTED` | `Regularization status must be APPROVED or REJECTED` |
| Already decided with the other outcome | `Regularization is already {currentStatus} and cannot be changed to {newStatus}` |

#### Example

```bash
curl -X PUT 'http://localhost:9091/employees/3f6c1c1e-8a0b-4d7e-9b7a-2f1d5c0e9a11/regularizations/42/status?status=APPROVED'
```

---

### 4.5 Delete a regularization

Withdraws a regularization request that is still awaiting a decision.

```
DELETE /employees/{employeeId}/regularizations/{id}
```

**Auth: required** (token `sub` must equal `employeeId`)

| Parameter | In | Type | Required | Description |
|---|---|---|---|---|
| `employeeId` | path | string | yes | Owner of the regularization |
| `id` | path | long | yes | Regularization id |
| `Authorization` | header | string | yes | See [1.1](#11-authorization-header) |

Request body: none.

#### Success — `204 No Content`

Empty body. The regularization row is deleted and the manager's linked action item (if any) is
removed from the Utility service.

#### Errors — `400 Bad Request`

| Condition | `message` |
|---|---|
| Token `sub` ≠ `employeeId` | `Regularization requests can only be deleted for your own employee id.` |
| No regularization with this `id` | `Regularization not found` |
| It belongs to another employee | `Regularization does not belong to the specified employee` |
| Status is not `PENDING` | `Regularization request is already {status} and cannot be deleted. Only a pending regularization request can be deleted.` |

Plus the token errors in [1.2](#12-error-response).

#### Example

```bash
curl -X DELETE 'http://localhost:9091/employees/3f6c1c1e-8a0b-4d7e-9b7a-2f1d5c0e9a11/regularizations/42' \
  -H 'Authorization: Bearer <jwt>'
```

---

## 5. Shared behaviour of the three delete endpoints

- Only a request in **pending** status can be deleted (compared case-insensitively). Approved and
  rejected requests are permanent.
- Deletion is a hard delete; the row is removed, not marked cancelled.
- The delete runs in one transaction with the action-item removal. If the Utility service call
  fails, the endpoint returns `500` and the request row is **not** deleted.
- A request with no linked action item (for example, raised before a manager was assigned) deletes
  without calling the Utility service.
- Repeating a successful delete returns `400` with the "not found" message, not `204`.
