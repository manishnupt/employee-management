package com.hrms.employee.management.utility;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/** Counts the days of a leave that consume balance: Saturdays and Sundays are not counted. */
public final class WorkingDays {

    private static final Set<DayOfWeek> WEEKENDS = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    private WorkingDays() {
    }

    /** Working days from {@code startDate} to {@code endDate}, both inclusive. 0 if the range is empty. */
    public static long between(LocalDate startDate, LocalDate endDate) {
        long days = 0;
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            if (!WEEKENDS.contains(date.getDayOfWeek())) {
                days++;
            }
        }
        return days;
    }
}
