package com.hrms.employee.management.utility;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;

public class ProrataLeaveCalculator {

    public enum Frequency {
        YEARLY(1),
        HALF_YEARLY(2),
        QUARTERLY(4),
        MONTHLY(12);

        final int periodsPerYear;

        Frequency(int periodsPerYear) {
            this.periodsPerYear = periodsPerYear;
        }
    }

    private static final Map<Frequency, Integer> MONTHS_PER_PERIOD = new EnumMap<>(Frequency.class);
    static {
        MONTHS_PER_PERIOD.put(Frequency.YEARLY, 12);
        MONTHS_PER_PERIOD.put(Frequency.HALF_YEARLY, 6);
        MONTHS_PER_PERIOD.put(Frequency.QUARTERLY, 3);
        MONTHS_PER_PERIOD.put(Frequency.MONTHLY, 1);
    }

    /**
     * Calculates the prorated leave entitlement for the disbursal period the employee joins in
     * (the month, quarter, half-year or year containing {@code joinDate}). Later periods of the
     * cycle are not included: the disbursal scheduler credits those when they start.
     *
     * @param joinDate         employee's date of joining
     * @param annualLeaveCount total annual leave count (e.g. 24)
     * @param frequency        disbursal frequency
     * @param cycleStart       start date of the leave cycle (e.g. Jan 1 or fiscal start)
     * @param cycleEnd         end date of the leave cycle (e.g. Dec 31 or fiscal end)
     * @return prorated leave count for the join period, rounded to the nearest 0.25
     */
    public static double calculateProrataLeaves(
            LocalDate joinDate,
            double annualLeaveCount,
            Frequency frequency,
            LocalDate cycleStart,
            LocalDate cycleEnd) {

        if (joinDate.isAfter(cycleEnd)) {
            return 0.0; // joined after the cycle ended — no leaves for this cycle
        }
        if (joinDate.isBefore(cycleStart)) {
            joinDate = cycleStart; // clamp: treat as joining at cycle start
        }

        double leavesPerPeriod = annualLeaveCount / frequency.periodsPerYear;
        int monthsPerPeriod = MONTHS_PER_PERIOD.get(frequency);

        LocalDate periodStart = cycleStart;

        while (!periodStart.isAfter(cycleEnd)) {
            LocalDate periodEnd = periodStart.plusMonths(monthsPerPeriod).minusDays(1);
            if (periodEnd.isAfter(cycleEnd)) {
                periodEnd = cycleEnd;
            }

            if (!joinDate.isAfter(periodEnd)) {
                // This is the join period — prorate it. Later periods are credited by the
                // disbursal scheduler, so counting them here would credit them twice.
                long totalDays = ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;
                long remainingDays = ChronoUnit.DAYS.between(joinDate, periodEnd) + 1;
                double prorated = leavesPerPeriod * ((double) remainingDays / totalDays);
                return Math.round(prorated * 4.0) / 4.0;
            }
            // periods before the join period contribute 0

            periodStart = periodEnd.plusDays(1);
        }

        return 0.0;
    }

    // Demo / test
    public static void main(String[] args) {
        LocalDate joinDate = LocalDate.of(2026, 8, 16);
        LocalDate cycleStart = LocalDate.of(2026, 1, 1);
        LocalDate cycleEnd = LocalDate.of(2026, 12, 31);
        double annualLeaveCount = 24;

        for (Frequency freq : Frequency.values()) {
            double leaves = calculateProrataLeaves(joinDate, annualLeaveCount, freq, cycleStart, cycleEnd);
            System.out.println(freq + " -> " + leaves + " leaves");
        }
    }
}