package com.hrms.employee.management.utility;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.hrms.employee.management.dao.Employee;

class DisbursalEligibilityTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 10, 1);

    private static Employee employee(String jobStatus, boolean deleted, LocalDateTime createdAt) {
        Employee employee = new Employee();
        employee.setJobStatus(jobStatus);
        employee.setDeleted(deleted);
        employee.setCreatedAt(createdAt);
        return employee;
    }

    @Test
    void activeEmployeeFromAnEarlierPeriodAccrues() {
        assertTrue(DisbursalEligibility.accruesForPeriod(
                employee("Active", false, LocalDateTime.of(2026, 9, 30, 23, 59)), PERIOD_START));
    }

    @Test
    void deletedEmployeeDoesNotAccrue() {
        assertFalse(DisbursalEligibility.accruesForPeriod(
                employee("Active", true, LocalDateTime.of(2026, 1, 5, 10, 0)), PERIOD_START));
    }

    @Test
    void inactiveJobStatusDoesNotAccrue() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 5, 10, 0);
        assertFalse(DisbursalEligibility.accruesForPeriod(employee("Exited", false, createdAt), PERIOD_START));
        assertFalse(DisbursalEligibility.accruesForPeriod(employee("Notice", false, createdAt), PERIOD_START));
    }

    @Test
    void jobStatusMatchIgnoresCaseAndBlankCountsAsActive() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 5, 10, 0);
        assertTrue(DisbursalEligibility.isActive(employee(" active ", false, createdAt)));
        assertTrue(DisbursalEligibility.isActive(employee(null, false, createdAt)));
        assertTrue(DisbursalEligibility.isActive(employee("", false, createdAt)));
    }

    @Test
    void joinerInThePeriodIsLeftToProrata() {
        // Created on the period start or later: init already credited this period's share.
        assertFalse(DisbursalEligibility.accruesForPeriod(
                employee("Active", false, LocalDateTime.of(2026, 10, 1, 0, 0)), PERIOD_START));
        assertFalse(DisbursalEligibility.accruesForPeriod(
                employee("Active", false, LocalDateTime.of(2026, 10, 20, 9, 0)), PERIOD_START));
    }

    @Test
    void periodStartPerFrequency() {
        LocalDate date = LocalDate.of(2026, 8, 16);
        assertEquals(LocalDate.of(2026, 8, 1), DisbursalFrequency.MONTHLY.periodStart(date));
        assertEquals(LocalDate.of(2026, 7, 1), DisbursalFrequency.QUARTERLY.periodStart(date));
        assertEquals(LocalDate.of(2026, 7, 1), DisbursalFrequency.HALF_YEARLY.periodStart(date));
        assertEquals(LocalDate.of(2026, 1, 1), DisbursalFrequency.YEARLY.periodStart(date));
        assertEquals(LocalDate.of(2026, 1, 1), DisbursalFrequency.HALF_YEARLY.periodStart(LocalDate.of(2026, 6, 30)));
        assertEquals(LocalDate.of(2026, 10, 1), DisbursalFrequency.QUARTERLY.periodStart(LocalDate.of(2026, 12, 31)));
    }

    @Test
    void wholeDaysAddUpToTheAnnualTotal() {
        for (DisbursalFrequency frequency : DisbursalFrequency.values()) {
            for (int annualDays : new int[] { 0, 1, 10, 12, 18, 24, 25 }) {
                int sum = 0;
                for (int month = 1; month <= 12; month += 12 / frequency.getPeriodsPerYear()) {
                    sum += frequency.wholeDaysForPeriod(annualDays, LocalDate.of(2026, month, 1));
                }
                assertEquals(annualDays, sum, frequency + " " + annualDays);
            }
        }
    }

    @Test
    void wholeDaysForUnevenSplits() {
        // 10/yr monthly used to truncate to 0 every month
        int[] expected = { 1, 1, 1, 0, 1, 1, 1, 1, 1, 0, 1, 1 };
        for (int month = 1; month <= 12; month++) {
            assertEquals(expected[month - 1], DisbursalFrequency.MONTHLY.wholeDaysForPeriod(10, LocalDate.of(2026, month, 15)));
        }
        // 18/yr quarterly used to give 4 per quarter (16/yr)
        assertEquals(5, DisbursalFrequency.QUARTERLY.wholeDaysForPeriod(18, LocalDate.of(2026, 2, 1)));
        assertEquals(4, DisbursalFrequency.QUARTERLY.wholeDaysForPeriod(18, LocalDate.of(2026, 5, 1)));
        assertEquals(5, DisbursalFrequency.QUARTERLY.wholeDaysForPeriod(18, LocalDate.of(2026, 8, 1)));
        assertEquals(4, DisbursalFrequency.QUARTERLY.wholeDaysForPeriod(18, LocalDate.of(2026, 11, 1)));
        assertEquals(2, DisbursalFrequency.MONTHLY.wholeDaysForPeriod(24, LocalDate.of(2026, 10, 5)));
    }
}
