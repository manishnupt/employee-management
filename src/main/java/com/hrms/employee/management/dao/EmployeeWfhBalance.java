package com.hrms.employee.management.dao;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

@Data
@Entity
public class EmployeeWfhBalance {


    @Id
    private Long id;
    private String wfhTypeName;// quarterly,monthly,yearly,half_yearly
    private String employeeId;
    private Integer wfhBalance;

}
