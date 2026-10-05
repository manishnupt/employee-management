package com.hrms.employee.management.utility;

public enum LeaveTransactionType {
    CREDIT,
    DEBIT,
    /** Carried-forward days credited into the new year's balance. */
    CARRY_FORWARD,
    /** Carried-forward days moved out of the closing year's balance. */
    CARRY_FORWARD_OUT,
    /** Days forfeited at a period or year end (no carry forward, over the cap, type discontinued, employee deleted). */
    LAPSE,
    INITIALIZATION,
    ADJUSTMENT
}
