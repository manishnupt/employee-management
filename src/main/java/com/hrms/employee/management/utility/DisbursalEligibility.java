package com.hrms.employee.management.utility;

import java.time.LocalDate;

import com.hrms.employee.management.dao.Employee;

/** Decides which employees accrue leave / WFH balance. */
public final class DisbursalEligibility {

    public static final String ACTIVE_JOB_STATUS = "Active";

    private DisbursalEligibility() {
    }

    /**
     * Not soft-deleted and job status is Active. A blank job status counts as active, because the
     * field is optional on employee create/update and must not silently stop accruals.
     */
    public static boolean isActive(Employee employee) {
        if (employee.isDeleted()) {
            return false;
        }
        String jobStatus = employee.getJobStatus();
        return jobStatus == null || jobStatus.isBlank() || ACTIVE_JOB_STATUS.equalsIgnoreCase(jobStatus.trim());
    }

    /**
     * Whether the scheduler should credit {@code employee} for the period starting on {@code periodStart}.
     * Employees created on or after the period start are skipped: they were already credited their
     * prorated share of this period at initialization. The employee's creation date stands in for the
     * joining date, which is also what the prorata calculation uses.
     */
    public static boolean accruesForPeriod(Employee employee, LocalDate periodStart) {
        if (!isActive(employee)) {
            return false;
        }
        return employee.getCreatedAt() == null || employee.getCreatedAt().toLocalDate().isBefore(periodStart);
    }
}
