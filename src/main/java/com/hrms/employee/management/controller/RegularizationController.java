package com.hrms.employee.management.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.hrms.employee.management.dto.RegularizationDto;
import com.hrms.employee.management.exceptions.BusinessException;
import com.hrms.employee.management.service.RegularizationService;
import com.hrms.employee.management.utility.JwtUtil;

@RestController
@RequestMapping("/employees/{employeeId}/regularizations")
@CrossOrigin(origins ="*")
public class RegularizationController {

    private final RegularizationService regularizationService;

    public RegularizationController(RegularizationService regularizationService) {
        this.regularizationService = regularizationService;
    }

    @PostMapping
    public ResponseEntity<RegularizationDto> raiseRegularization(@PathVariable String employeeId,
                                                                 @RequestHeader(value = "Authorization") String authorization,
                                                                 @RequestBody RegularizationDto regularizationDto) {
        String userId = JwtUtil.extractUserId(authorization);
        if (!userId.equals(employeeId)) {
            throw new BusinessException("Regularization can only be raised for your own employee id.");
        }
        RegularizationDto regularization = regularizationService.raiseRegularization(employeeId, regularizationDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(regularization);
    }

    @GetMapping
    public ResponseEntity<List<RegularizationDto>> getRegularizationHistory(@PathVariable String employeeId) {
        return ResponseEntity.ok(regularizationService.getRegularizationHistory(employeeId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RegularizationDto> getRegularizationById(@PathVariable String employeeId, @PathVariable Long id) {
        return ResponseEntity.ok(regularizationService.getRegularizationById(employeeId, id));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<RegularizationDto> updateRegularizationStatus(@PathVariable String employeeId, @PathVariable Long id, @RequestParam String status) {
        RegularizationDto updated = regularizationService.updateRegularizationStatus(employeeId, id, status);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRegularization(@PathVariable String employeeId, @PathVariable Long id,
                                                     @RequestHeader(value = "Authorization") String authorization) {
        String userId = JwtUtil.extractUserId(authorization);
        if (!userId.equals(employeeId)) {
            throw new BusinessException("Regularization requests can only be deleted for your own employee id.");
        }
        regularizationService.deleteRegularization(employeeId, id);
        return ResponseEntity.noContent().build();
    }
}
