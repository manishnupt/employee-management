package com.hrms.employee.management.dto;

import lombok.Data;

/** Item of the admin service's GET /admin/group response. */
@Data
public class GroupExtResponse {
    private Long groupId;
    private String groupName;
    private String groupDescription;

    public RoleGroupExtResponse toRoleGroup() {
        RoleGroupExtResponse group = new RoleGroupExtResponse();
        group.setId(groupId);
        group.setName(groupName);
        group.setDescription(groupDescription);
        return group;
    }
}
