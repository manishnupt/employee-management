package com.hrms.employee.management.controller;

import java.util.List;

import com.hrms.employee.management.dto.EmployeeUiResponse;
import com.hrms.employee.management.utility.EmployeeSearchType;
import com.hrms.employee.management.utility.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dto.EmployeeCountDto;
import com.hrms.employee.management.dto.EmployeeDto;
import com.hrms.employee.management.dto.EmployeeReportResponse;
import com.hrms.employee.management.service.EmployeeService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("/employee")
@CrossOrigin(origins ="*")
@Log4j2
public class EmployeeController {
	private final EmployeeService employeeService;

    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @PostMapping
    public ResponseEntity<Employee> createEmployee(@RequestBody EmployeeDto employeeDto) {
        log.info("createEmployee called for employeeEmail={}", employeeDto.getEmail());
        String userId=employeeService.onboardUserInKeycloak(employeeDto, TenantContext.getCurrentTenant());
       // employeeDto.setKcReferenceId(userId);
        Employee createdEmployee = employeeService.createEmployee(employeeDto,userId);
        return ResponseEntity.ok(createdEmployee);
    }


    @PutMapping("/{employeeId}")
    public ResponseEntity<Employee> updateEmployee(@PathVariable String employeeId, @RequestBody EmployeeDto employeeDto) {
        log.info("updateEmployee called for employeeId={}", employeeId);
        Employee updatedEmployee = employeeService.updateEmployee(employeeId, employeeDto);
        return ResponseEntity.ok(updatedEmployee);
    }

    @GetMapping("/{employeeId}")
    public ResponseEntity<EmployeeUiResponse> getEmployeeById(@PathVariable String employeeId) {
        log.info("getEmployeeById called for employeeId={}", employeeId);
        EmployeeUiResponse employee = employeeService.getEmployeeById(employeeId);
        return ResponseEntity.ok(employee);
    }

    @GetMapping("/report/{employeeId}")
    public ResponseEntity<EmployeeReportResponse> getEmployeeReportById(@PathVariable String employeeId,@RequestParam int month,@RequestParam int year) {
        log.info("getEmployeeReportById called for employeeId={} month={} year={}", employeeId, month, year);
        EmployeeReportResponse employee = employeeService.getEmployeeReportById(employeeId,month,year);
        return ResponseEntity.ok(employee);
    }

    @GetMapping
    public ResponseEntity<Page<Employee>> getAllEmployees(
            @RequestParam(required = false) EmployeeSearchType searchType,
            @RequestParam(required = false) List<String> values,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        log.info("getAllEmployees called with searchType={} values={} page={} size={}", searchType, values, page, size);
        Page<Employee> employees = employeeService.getAllEmployees(searchType, values, page, size);
        return ResponseEntity.ok(employees);
    }

    @GetMapping("/counts")
    public ResponseEntity<EmployeeCountDto> getEmployeeCounts() {
        log.info("getEmployeeCounts called");
        EmployeeCountDto employeeCounts = employeeService.getEmployeeCounts();
        return ResponseEntity.ok(employeeCounts);
    }

    @GetMapping("/unassigned")
    public ResponseEntity<List<Employee>> getUnassignedEmployees() {
        log.info("getUnassignedEmployees called");
        List<Employee> unassignedEmployees = employeeService.findUnassignedEmployees();
        return ResponseEntity.ok(unassignedEmployees);
    }

    @GetMapping("/by-group/{groupId}")
    public ResponseEntity<List<Employee>> getEmployeesByGroup(@PathVariable Long groupId) {
        log.info("getEmployeesByGroup called for groupId={}", groupId);
        List<Employee> employees = employeeService.findEmployeesByGroup(groupId);
        return ResponseEntity.ok(employees);
    }
    @PatchMapping("/{employeeId}/assign-group/{groupId}")
    public ResponseEntity<?> assignGroupToEmployee(
            HttpServletRequest request,
            @PathVariable String employeeId,
            @PathVariable Long groupId) {
        log.info("assignGroupToEmployee called for employeeId={} groupId={}", employeeId, groupId);
        employeeService.assignGroupToEmployee(request.getHeader("authorization"),employeeId, groupId);
        return ResponseEntity.ok("group assigned successfully to employee");
    }
    @DeleteMapping("/{employeeId}/unassign-group")
    public ResponseEntity<?> unassignGroupFromEmployee(
            HttpServletRequest request,
            @PathVariable String employeeId) {
        log.info("unassignGroupFromEmployee called for employeeId={}", employeeId);
        employeeService.unassignGroupFromEmployee(request.getHeader("authorization"), employeeId);
        return ResponseEntity.ok("group unassigned successfully from employee");
    }

    @PatchMapping("/{employeeId}/assign-manager/{managerEmpId}")
    public ResponseEntity<?> assignManagerToEmployee(
            @PathVariable String employeeId,
            @PathVariable String managerEmpId) {
        log.info("assignManagerToEmployee called for employeeId={} managerEmpId={}", employeeId, managerEmpId);
        employeeService.assignManagerToEmployee(employeeId, managerEmpId);
        return ResponseEntity.ok("manager assigned successfully to employee");
    }

    @DeleteMapping("/{employeeId}/unassign-manager")
    public ResponseEntity<?> unassignManagerToEmployee(
            @PathVariable String employeeId) {
        log.info("unassignManagerToEmployee called for employeeId={}", employeeId);
        employeeService.unassignManagerToEmployee(employeeId);
        return ResponseEntity.ok("manager unassigned successfully from employee");
    }

    @DeleteMapping("/{employeeId}")
    public ResponseEntity<?> deleteEmployee(@PathVariable String employeeId) {
        log.info("deleteEmployee called for employeeId={}", employeeId);
        employeeService.deleteEmployee(employeeId);
        return ResponseEntity.ok("employee deleted successfully");
    }

    @GetMapping("/getEmployeeByKcRefId")
    public ResponseEntity<Employee> getEmployeeByKcRefId(@RequestParam String kcRefId) {
        log.info("getEmployeeByKcRefId called for kcRefId={}", kcRefId);
        Employee employee = employeeService.findEmployeesByKcRefId(kcRefId);
        return ResponseEntity.ok(employee);
    }
}
