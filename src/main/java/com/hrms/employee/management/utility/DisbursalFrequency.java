package com.hrms.employee.management.utility;

import java.time.LocalDate;

public enum DisbursalFrequency {
    MONTHLY(12),
    QUARTERLY(4),
    HALF_YEARLY(2),
    YEARLY(1);

    private final int periodsPerYear;

    DisbursalFrequency(int periodsPerYear) {
        this.periodsPerYear = periodsPerYear;
    }

    public int getPeriodsPerYear() {
        return periodsPerYear;
    }

    /** Path segment used by the company service: /leave-types/schedule/{segment}. */
    public String getScheduleSegment() {
        return name().toLowerCase();
    }

    /**
     * Identifies the disbursal period containing {@code date}, e.g. 2026-10, 2026-Q4, 2026-H2, 2026.
     * Used as part of the idempotency key in disbursal_run.
     */
    public String periodKey(LocalDate date) {
        int year = date.getYear();
        int month = date.getMonthValue();
        switch (this) {
            case MONTHLY:
                return String.format("%d-%02d", year, month);
            case QUARTERLY:
                return year + "-Q" + ((month - 1) / 3 + 1);
            case HALF_YEARLY:
                return year + "-H" + (month <= 6 ? 1 : 2);
            default:
                return String.valueOf(year);
        }
    }

    /** First day of the disbursal period containing {@code date}, e.g. 1 Oct for 2026-Q4. */
    public LocalDate periodStart(LocalDate date) {
        int monthsPerPeriod = 12 / periodsPerYear;
        int firstMonth = (date.getMonthValue() - 1) / monthsPerPeriod * monthsPerPeriod + 1;
        return LocalDate.of(date.getYear(), firstMonth, 1);
    }

    /**
     * Whole days to credit for the period containing {@code date} when {@code annualDays} is spread
     * over the year. Periods get the rounded running total minus what earlier periods got, so the
     * year always adds up to exactly {@code annualDays}: 10/yr monthly gives 1,1,1,0,1,1,1,1,1,0,1,1
     * rather than 0 or 1 every month.
     */
    public int wholeDaysForPeriod(int annualDays, LocalDate date) {
        int monthsPerPeriod = 12 / periodsPerYear;
        int periodIndex = (date.getMonthValue() - 1) / monthsPerPeriod + 1;
        long upToThisPeriod = Math.round((double) annualDays * periodIndex / periodsPerYear);
        long upToLastPeriod = Math.round((double) annualDays * (periodIndex - 1) / periodsPerYear);
        return (int) (upToThisPeriod - upToLastPeriod);
    }
}
