package com.hrms.employee.management.specification;

import java.util.List;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.CollectionUtils;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.utility.EmployeeSearchType;

import jakarta.persistence.criteria.Predicate;

public class EmployeeSpecification {

    private EmployeeSpecification() {
    }

    public static Specification<Employee> search(EmployeeSearchType searchType, List<String> values) {
        return (root, query, cb) -> {
            var predicate = cb.isFalse(root.get("deleted"));

            if (searchType == null || CollectionUtils.isEmpty(values)) {
                return predicate;
            }

            String field = resolveField(searchType);
            var valuePredicates = values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> cb.like(cb.lower(root.get(field)), "%" + value.toLowerCase() + "%"))
                    .toArray(Predicate[]::new);

            if (valuePredicates.length == 0) {
                return predicate;
            }

            return cb.and(predicate, cb.or(valuePredicates));
        };
    }

    private static String resolveField(EmployeeSearchType searchType) {
        return switch (searchType) {
            case EMPLOYEE_ID -> "employeeId";
            case NAME -> "name";
            case EMAIL -> "email";
            case PHONE -> "phone";
            case PROJECT -> "project";
        };
    }
}
