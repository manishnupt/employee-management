package com.hrms.employee.management.utility;

import com.hrms.employee.management.dao.LeaveTracker;
import com.hrms.employee.management.dao.Regularization;
import com.hrms.employee.management.dao.Timesheet;
import com.hrms.employee.management.dao.WFHTracker;
import com.hrms.employee.management.dto.ActionItemExtRequest;

public class ActionItemHelper {
    public static ActionItemExtRequest convertToLeaveRequest(LeaveTracker leaveTracker, String employeeId, String assignedManagerId) {

        return ActionItemExtRequest.builder().
                initiatorUserId(employeeId).
                title("Leave Application Notification").
                type(ActionItemExtRequest.ActionType.LEAVE).
                assigneeUserId(assignedManagerId).
                referenceId(leaveTracker.getId()).
                build();

    }

    public static ActionItemExtRequest convertToTimesheetequest(Timesheet timesheet, String employeeId, String assignedManagerId) {

        return ActionItemExtRequest.builder().
                initiatorUserId(employeeId).
                title("Timesheet Notification").
                type(ActionItemExtRequest.ActionType.TIMESHEET).
                assigneeUserId(assignedManagerId).
                referenceId(timesheet.getId()).
                build();

    }

    public static ActionItemExtRequest convertToWFHRequest(WFHTracker wfhTracker, String employeeId,
            String assignedManagerId) {
        return ActionItemExtRequest.builder().
                initiatorUserId(employeeId).
                title("Work From Home Notification").
                type(ActionItemExtRequest.ActionType.WFH).
                assigneeUserId(assignedManagerId).
                referenceId(wfhTracker.getId()).
                build();

    }

    public static ActionItemExtRequest convertToRegularizationRequest(Regularization regularization, String employeeId,
            String assignedManagerId) {
        return ActionItemExtRequest.builder().
                initiatorUserId(employeeId).
                title("Regularization Notification").
                type(ActionItemExtRequest.ActionType.REGULARIZATION).
                assigneeUserId(assignedManagerId).
                referenceId(regularization.getId()).
                build();

    }
}
