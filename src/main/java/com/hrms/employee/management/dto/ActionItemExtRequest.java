package com.hrms.employee.management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Data
public class ActionItemExtRequest {

    private String title;

    private String description;

    private ActionType type;

    private String initiatorUserId;

    private String assigneeUserId;

    private Long referenceId;

    public enum ActionType {
        TIMESHEET, LEAVE, WFH, EXPENSE, ASSET_REQUEST, REGULARIZATION
    }
}
