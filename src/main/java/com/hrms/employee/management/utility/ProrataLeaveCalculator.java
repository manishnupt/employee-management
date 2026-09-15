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
     * Calculates prorated leave entitlement for an employee joining mid-cycle.
     *
     * @param joinDate         employee's date of joining
     * @param annualLeaveCount total annual leave count (e.g. 24)
     * @param frequency        disbursal frequency
     * @param cycleStart       start date of the leave cycle (e.g. Jan 1 or fiscal start)
     * @param cycleEnd         end date of the leave cycle (e.g. Dec 31 or fiscal end)
     * @return prorated leave count, rounded to 2 decimal places
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
        double totalLeaves = 0.0;
        boolean foundJoinPeriod = false;

        while (periodStart.isBefore(cycleEnd) || periodStart.isEqual(cycleEnd)) {
            LocalDate periodEnd = periodStart.plusMonths(monthsPerPeriod).minusDays(1);
            if (periodEnd.isAfter(cycleEnd)) {
                periodEnd = cycleEnd;
            }

            if (!foundJoinPeriod
                    && !joinDate.isBefore(periodStart)
                    && !joinDate.isAfter(periodEnd)) {
                // This is the join period — prorate it
                long totalDays = ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;
                long remainingDays = ChronoUnit.DAYS.between(joinDate, periodEnd) + 1;
                totalLeaves += leavesPerPeriod * ((double) remainingDays / totalDays);
                foundJoinPeriod = true;
            } else if (foundJoinPeriod) {
                // Full period after the join period
                totalLeaves += leavesPerPeriod;
            }
            // periods before the join period contribute 0

            periodStart = periodEnd.plusDays(1);
        }

        return Math.round(totalLeaves * 100.0) / 100.0;
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