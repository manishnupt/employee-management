package com.hrms.employee.management.dao;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

@Data
@Entity
public class EmployeeWfhBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String wfhTypeName;// quarterly,monthly,yearly,half_yearly
    private String employeeId;
    private Integer wfhBalance;

}
