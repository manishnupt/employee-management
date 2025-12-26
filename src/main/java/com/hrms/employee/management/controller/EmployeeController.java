package com.hrms.employee.management.controller;

import java.util.List;

import com.hrms.employee.management.utility.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dto.EmployeeCountDto;
import com.hrms.employee.management.dto.EmployeeDto;
import com.hrms.employee.management.dto.EmployeeReportResponse;
import com.hrms.employee.management.service.EmployeeService;

@RestController
@RequestMapping("/employee")
@CrossOrigin(origins ="*")
public class EmployeeController {
	private final EmployeeService employeeService;

    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @PostMapping
    public ResponseEntity<Employee> createEmployee(@RequestBody EmployeeDto employeeDto) {
        String userId=employeeService.onboardUserInKeycloak(employeeDto, TenantContext.getCurrentTenant());
       // employeeDto.setKcReferenceId(userId);
        Employee createdEmployee = employeeService.createEmployee(employeeDto,userId);
        return ResponseEntity.ok(createdEmployee);
    }


    @PutMapping("/{employeeId}")
    public ResponseEntity<Employee> updateEmployee(@PathVariable String employeeId, @RequestBody EmployeeDto employeeDto) {
        Employee updatedEmployee = employeeService.updateEmployee(employeeId, employeeDto);
        return ResponseEntity.ok(updatedEmployee);
    }

    @GetMapping("/{employeeId}")
    public ResponseEntity<Employee> getEmployeeById(@PathVariable String employeeId) {
        Employee employee = employeeService.getEmployeeById(employeeId);
        return ResponseEntity.ok(employee);
    }

    @GetMapping("/report/{employeeId}")
    public ResponseEntity<EmployeeReportResponse> getEmployeeReportById(@PathVariable String employeeId,@RequestParam int month,@RequestParam int year) {
        EmployeeReportResponse employee = employeeService.getEmployeeReportById(employeeId,month,year);
        return ResponseEntity.ok(employee);
    }

    @GetMapping
    public ResponseEntity<List<Employee>> getAllEmployees() {
        List<Employee> employees = employeeService.getAllEmployees();
        return ResponseEntity.ok(employees);
    }
    
    @GetMapping("/counts")
    public ResponseEntity<EmployeeCountDto> getEmployeeCounts() {
        EmployeeCountDto employeeCounts = employeeService.getEmployeeCounts();
        return ResponseEntity.ok(employeeCounts);
    }

    @GetMapping("/unassigned")
    public ResponseEntity<List<Employee>> getUnassignedEmployees() {
        List<Employee> unassignedEmployees = employeeService.findUnassignedEmployees();
        return ResponseEntity.ok(unassignedEmployees);
    }

    @GetMapping("/by-group/{groupId}")
    public ResponseEntity<List<Employee>> getEmployeesByGroup(@PathVariable Long groupId) {
        List<Employee> employees = employeeService.findEmployeesByGroup(groupId);
        return ResponseEntity.ok(employees);
    }
    @PatchMapping("/{employeeId}/assign-group/{groupId}")
    public ResponseEntity<?> assignGroupToEmployee(
            HttpServletRequest request,
            @PathVariable String employeeId,
            @PathVariable Long groupId) {
        employeeService.assignGroupToEmployee(request.getHeader("authorization"),employeeId, groupId);
        return ResponseEntity.ok("group assigned successfully to employee");
    }
    @DeleteMapping("/{employeeId}/unassign-group")
    public ResponseEntity<?> unassignGroupFromEmployee(
            HttpServletRequest request,
            @PathVariable String employeeId) {
        employeeService.unassignGroupFromEmployee(request.getHeader("authorization"), employeeId);
        return ResponseEntity.ok("group unassigned successfully from employee");
    }

    @PatchMapping("/{employeeId}/assign-manager/{managerEmpId}")
    public ResponseEntity<?> assignManagerToEmployee(
            @PathVariable String employeeId,
            @PathVariable String managerEmpId) {
        employeeService.assignManagerToEmployee(employeeId, managerEmpId);
        return ResponseEntity.ok("manager assigned successfully to employee");
    }

    @DeleteMapping("/{employeeId}/unassign-manager")
    public ResponseEntity<?> unassignManagerToEmployee(
            @PathVariable String employeeId) {
        employeeService.unassignManagerToEmployee(employeeId);
        return ResponseEntity.ok("manager unassigned successfully from employee");
    }

    @GetMapping("/getEmployeeByKcRefId")
    public ResponseEntity<Employee> getEmployeeByKcRefId(@RequestParam String kcRefId) {
        Employee employee = employeeService.findEmployeesByKcRefId(kcRefId);
        return ResponseEntity.ok(employee);
    }
}
