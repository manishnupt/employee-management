package com.hrms.employee.management.specification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.utility.EmployeeSearchType;

class EmployeeSpecificationTest {

    private static Employee employee(String name, boolean deleted) {
        Employee e = new Employee();
        e.setEmployeeId("id-1");
        e.setName(name);
        e.setDeleted(deleted);
        return e;
    }

    @Test
    void deletedEmployeesNeverMatch() {
        assertFalse(EmployeeSpecification.matches(employee("Ajay", true), null, null));
    }

    @Test
    void noFilterMatchesEveryActiveEmployee() {
        assertTrue(EmployeeSpecification.matches(employee("Ajay", false), null, null));
        assertTrue(EmployeeSpecification.matches(employee("Ajay", false), EmployeeSearchType.NAME, List.of()));
        assertTrue(EmployeeSpecification.matches(employee("Ajay", false), EmployeeSearchType.NAME, Arrays.asList(" ", null)));
    }

    @Test
    void prefixMatchIsCaseInsensitiveAndOred() {
        Employee ajay = employee("Ajay Parkhe", false);
        assertTrue(EmployeeSpecification.matches(ajay, EmployeeSearchType.NAME, List.of("aj")));
        assertTrue(EmployeeSpecification.matches(ajay, EmployeeSearchType.NAME, List.of("zz", "AJAY")));
        assertFalse(EmployeeSpecification.matches(ajay, EmployeeSearchType.NAME, List.of("parkhe")));
    }

    @Test
    void nullFieldDoesNotMatch() {
        assertFalse(EmployeeSpecification.matches(employee("Ajay", false), EmployeeSearchType.EMAIL, List.of("a")));
    }
}
