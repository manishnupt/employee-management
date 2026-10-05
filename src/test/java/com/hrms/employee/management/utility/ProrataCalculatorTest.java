package com.hrms.employee.management.utility;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class ProrataCalculatorTest {

    private static final LocalDate CYCLE_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate CYCLE_END = LocalDate.of(2026, 12, 31);

    private static double leaves(LocalDate joinDate, double annual, ProrataLeaveCalculator.Frequency frequency) {
        return ProrataLeaveCalculator.calculateProrataLeaves(joinDate, annual, frequency, CYCLE_START, CYCLE_END);
    }

    private static double wfh(LocalDate joinDate, double annual, ProrataWfhCalculator.Frequency frequency) {
        return ProrataWfhCalculator.calculateProrataWfh(joinDate, annual, frequency, CYCLE_START, CYCLE_END);
    }

    @Test
    void monthlyCreditsOnlyTheJoinMonth() {
        // 2/month, 16 of 31 days left in Aug -> 1.03 -> 1.0. Sep-Dec are left to the scheduler.
        assertEquals(1.0, leaves(LocalDate.of(2026, 8, 16), 24, ProrataLeaveCalculator.Frequency.MONTHLY));
    }

    @Test
    void quarterlyCreditsOnlyTheJoinQuarter() {
        // 6/quarter, 46 of 92 days left in Q3 -> 3.0. Q4 is left to the scheduler.
        assertEquals(3.0, leaves(LocalDate.of(2026, 8, 16), 24, ProrataLeaveCalculator.Frequency.QUARTERLY));
    }

    @Test
    void halfYearlyCreditsOnlyTheJoinHalf() {
        // 12/half, 135 of 181 days left in H1 -> 8.95 -> 9.0. H2 is left to the scheduler.
        assertEquals(9.0, leaves(LocalDate.of(2026, 2, 16), 24, ProrataLeaveCalculator.Frequency.HALF_YEARLY));
    }

    @Test
    void yearlyProratesTheRestOfTheYear() {
        // 138 of 365 days left -> 9.07 -> 9.0
        assertEquals(9.0, leaves(LocalDate.of(2026, 8, 16), 24, ProrataLeaveCalculator.Frequency.YEARLY));
    }

    @Test
    void joiningOnPeriodStartCreditsOneFullPeriod() {
        assertEquals(2.0, leaves(LocalDate.of(2026, 9, 1), 24, ProrataLeaveCalculator.Frequency.MONTHLY));
        assertEquals(6.0, leaves(LocalDate.of(2026, 10, 1), 24, ProrataLeaveCalculator.Frequency.QUARTERLY));
    }

    @Test
    void joiningBeforeCycleCreditsOnlyTheFirstPeriod() {
        assertEquals(2.0, leaves(LocalDate.of(2025, 11, 10), 24, ProrataLeaveCalculator.Frequency.MONTHLY));
    }

    @Test
    void joiningAfterCycleCreditsNothing() {
        assertEquals(0.0, leaves(LocalDate.of(2027, 1, 1), 24, ProrataLeaveCalculator.Frequency.MONTHLY));
    }

    @Test
    void wfhCreditsOnlyTheJoinPeriod() {
        assertEquals(1.0, wfh(LocalDate.of(2026, 8, 16), 24, ProrataWfhCalculator.Frequency.MONTHLY));
        assertEquals(3.0, wfh(LocalDate.of(2026, 8, 16), 24, ProrataWfhCalculator.Frequency.QUARTERLY));
        assertEquals(9.0, wfh(LocalDate.of(2026, 2, 16), 24, ProrataWfhCalculator.Frequency.HALF_YEARLY));
        assertEquals(9.0, wfh(LocalDate.of(2026, 8, 16), 24, ProrataWfhCalculator.Frequency.YEARLY));
    }
}
