package com.hrms.employee.management.utility;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class WorkingDaysTest {

    // 5 Oct 2026 is a Monday.
    private static LocalDate oct(int day) {
        return LocalDate.of(2026, 10, day);
    }

    @Test
    void countsWeekdaysInclusive() {
        assertEquals(1, WorkingDays.between(oct(5), oct(5)));
        assertEquals(5, WorkingDays.between(oct(5), oct(9)));
    }

    @Test
    void skipsWeekends() {
        assertEquals(2, WorkingDays.between(oct(9), oct(12)));
        assertEquals(10, WorkingDays.between(oct(5), oct(18)));
        assertEquals(0, WorkingDays.between(oct(10), oct(11)));
    }

    @Test
    void emptyRangeIsZero() {
        assertEquals(0, WorkingDays.between(oct(9), oct(5)));
    }
}
