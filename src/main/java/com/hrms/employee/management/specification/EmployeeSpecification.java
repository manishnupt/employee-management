package com.hrms.employee.management.specification;

import java.util.List;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.CollectionUtils;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.utility.EmployeeSearchType;

import jakarta.persistence.criteria.Predicate;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EmployeeSpecification {

    private EmployeeSpecification() {
    }

    public static Specification<Employee> search(EmployeeSearchType searchType, List<String> values) {
        return (root, query, cb) -> {
            log.info("Building employee search specification: searchType={}, values={}", searchType, values);
            var predicate = cb.isFalse(root.get("deleted"));

            if (searchType == null || CollectionUtils.isEmpty(values)) {
                log.info("No searchType/values supplied, returning only the non-deleted filter");
                return predicate;
            }

            String field = resolveField(searchType);
            log.info("Resolved searchType {} to entity field '{}'", searchType, field);
            var valuePredicates = values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> {
                        String pattern = value.toLowerCase() + "%";
                        log.info("Adding LIKE predicate: lower({}) LIKE '{}'", field, pattern);
                        return cb.like(cb.lower(root.get(field)), pattern);
                    })
                    .toArray(Predicate[]::new);

            if (valuePredicates.length == 0) {
                log.info("All supplied values were null/blank, returning only the non-deleted filter");
                return predicate;
            }

            log.info("Applying {} OR-ed LIKE predicate(s) on field '{}'", valuePredicates.length, field);
            return cb.and(predicate, cb.or(valuePredicates));
        };
    }

    /**
     * In-memory equivalent of {@link #search} (used for cached employees). Keep the two in sync:
     * not deleted, and the field starts with any of the values (case-insensitive).
     */
    public static boolean matches(Employee employee, EmployeeSearchType searchType, List<String> values) {
        if (employee.isDeleted()) {
            return false;
        }
        if (searchType == null || CollectionUtils.isEmpty(values)) {
            return true;
        }
        List<String> prefixes = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::toLowerCase)
                .toList();
        if (prefixes.isEmpty()) {
            return true;
        }
        String fieldValue = switch (searchType) {
            case EMPLOYEE_ID -> employee.getEmployeeId();
            case NAME -> employee.getName();
            case EMAIL -> employee.getEmail();
            case PHONE -> employee.getPhone();
            case PROJECT -> employee.getProject();
        };
        if (fieldValue == null) {
            return false;
        }
        String lower = fieldValue.toLowerCase();
        return prefixes.stream().anyMatch(lower::startsWith);
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
