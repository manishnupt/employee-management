package com.hrms.employee.management.utility;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public final class TimesheetUtil {

    private TimesheetUtil() {
    }

    /**
     * Calculates the total minutes worked between the start and end of a work period.
     * Using separate start and end dates supports shifts that cross midnight.
     *
     * @param workStartDate date on which the work started
     * @param workEndDate   date on which the work ended
     * @param clockIn       time of clock in on the start date
     * @param clockOut      time of clock out on the end date
     * @return total minutes worked
     * @throws IllegalArgumentException if any argument is null or the end is before the start
     */
    public static long calculateTotalMinutes(LocalDate workStartDate, LocalDate workEndDate,
                                             LocalTime clockIn, LocalTime clockOut) {
        if (workStartDate == null || workEndDate == null || clockIn == null || clockOut == null) {
            throw new IllegalArgumentException("workStartDate, workEndDate, clockIn and clockOut are required");
        }

        LocalDateTime start = LocalDateTime.of(workStartDate, clockIn);
        LocalDateTime end = LocalDateTime.of(workEndDate, clockOut);
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("Clock out (" + end + ") cannot be before clock in (" + start + ")");
        }

        return Duration.between(start, end).toMinutes();
    }

    /**
     * Converts total minutes into words, e.g. 176 -> "2 hours and 56 minutes".
     * Zero-valued parts are omitted ("2 hours", "56 minutes") and units are singular
     * where the value is 1 ("1 hour and 1 minute"). Zero minutes returns "0 minutes".
     *
     * @param totalMinutes total minutes worked, must not be negative
     * @return the duration in words
     * @throws IllegalArgumentException if totalMinutes is negative
     */
    public static String formatMinutesInWords(long totalMinutes) {
        if (totalMinutes < 0) {
            throw new IllegalArgumentException("totalMinutes cannot be negative: " + totalMinutes);
        }

        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;

        String hoursPart = hours + (hours == 1 ? " hour" : " hours");
        String minutesPart = minutes + (minutes == 1 ? " minute" : " minutes");

        if (hours == 0) {
            return minutesPart;
        }
        if (minutes == 0) {
            return hoursPart;
        }
        return hoursPart + " and " + minutesPart;
    }

    /**
     * Calculates the time worked between the start and end of a work period and returns it in words,
     * e.g. "2 hours and 56 minutes".
     *
     * @see #calculateTotalMinutes(LocalDate, LocalDate, LocalTime, LocalTime)
     * @see #formatMinutesInWords(long)
     */
    public static String calculateWorkedTimeInWords(LocalDate workStartDate, LocalDate workEndDate,
                                                    LocalTime clockIn, LocalTime clockOut) {
        return formatMinutesInWords(calculateTotalMinutes(workStartDate, workEndDate, clockIn, clockOut));
    }
}
