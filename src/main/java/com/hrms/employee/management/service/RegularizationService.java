package com.hrms.employee.management.service;

import java.time.LocalDate;
import java.util.List;

import com.hrms.employee.management.dao.Regularization;
import com.hrms.employee.management.dto.RegularizationDto;

public interface RegularizationService {

    RegularizationDto raiseRegularization(String employeeId, RegularizationDto regularizationDto);

    List<RegularizationDto> getRegularizationHistory(String employeeId);

    List<RegularizationDto> getRegularizationReportByEmployeeId(String employeeId, LocalDate startDate, LocalDate endDate);

    RegularizationDto getRegularizationById(String employeeId, Long id);

    RegularizationDto updateRegularizationStatus(String employeeId, Long id, String status);

    void deleteRegularization(String employeeId, Long id);

    List<Regularization> getUnassignedRegularizations(String employeeId);

    void saveLinkedActionItemId(Long id, Long actionItem);
}
