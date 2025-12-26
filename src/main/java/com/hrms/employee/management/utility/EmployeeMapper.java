package com.hrms.employee.management.utility;

import com.hrms.employee.management.dto.EmployeeUiResponse;
import com.hrms.employee.management.dto.GenerateTokenRequest;
import org.springframework.stereotype.Component;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dto.EmployeeDto;


import java.util.Map;

@Component
public class EmployeeMapper {

    public static GenerateTokenRequest getGenerateTokenRequest(Map<String, Object> configMap) {
        String clientId = (String) configMap.get("clientId");
        String clientSecret = (String) configMap.get("clientSecret");
        String username = (String) configMap.get("username");
        String password = (String) configMap.get("password");
        return new GenerateTokenRequest(password, username, clientId, clientSecret);
    }

    public Employee toEntity(EmployeeDto dto) {
        Employee employee = new Employee();
        employee.setName(dto.getName());
        employee.setUsername(dto.getUsername());
        employee.setRole(dto.getRole());
        employee.setEmail(dto.getEmail());
        employee.setPhone(dto.getPhone());
        employee.setAddress(dto.getAddress());
        employee.setCity(dto.getCity());
        employee.setState(dto.getState());
        employee.setZipCode(dto.getZipCode());
        employee.setCountry(dto.getCountry());
        employee.setJobTitle(dto.getJobTitle());
        employee.setProject(dto.getProject());
        employee.setJobType(dto.getJobType());
        employee.setJobStatus(dto.getJobStatus());
        employee.setJobDescription(dto.getJobDescription());
        return employee;
    }

    public void updateEntity(Employee employee, EmployeeDto dto) {
        employee.setName(dto.getName());
        employee.setUsername(dto.getUsername());
        employee.setRole(dto.getRole());
        employee.setEmail(dto.getEmail());
        employee.setPhone(dto.getPhone());
        employee.setAddress(dto.getAddress());
        employee.setCity(dto.getCity());
        employee.setState(dto.getState());
        employee.setZipCode(dto.getZipCode());
        employee.setCountry(dto.getCountry());
        employee.setJobTitle(dto.getJobTitle());
        employee.setProject(dto.getProject());
        employee.setJobType(dto.getJobType());
        employee.setJobStatus(dto.getJobStatus());
        employee.setJobDescription(dto.getJobDescription());
    }

    public EmployeeUiResponse toUiResponse(Employee employee, Employee manager) {
        EmployeeUiResponse response = new EmployeeUiResponse();
        response.setName(employee.getName());
        response.setUsername(employee.getUsername());
        response.setRole(employee.getRole());
        response.setEmail(employee.getEmail());
        response.setPhone(employee.getPhone());
        response.setAddress(employee.getAddress());
        response.setCity(employee.getCity());
        response.setState(employee.getState());
        response.setZipCode(employee.getZipCode());
        response.setCountry(employee.getCountry());
        response.setJobTitle(employee.getJobTitle());
        response.setProject(employee.getProject());
        response.setJobType(employee.getJobType());
        response.setJobStatus(employee.getJobStatus());
        response.setJobDescription(employee.getJobDescription());
        if(employee.getAssignedManagerId()!=null) {
            EmployeeUiResponse.ManagerInfoResponse managerInfo = new EmployeeUiResponse.ManagerInfoResponse();
            managerInfo.setManagerId(manager.getEmployeeId());
            managerInfo.setName(employee.getName());
            response.setAssignedManager(managerInfo);
        }
        return response;
    }
}