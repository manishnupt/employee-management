package com.hrms.employee.management.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hrms.employee.management.dto.WfhBalanceDto;
import com.hrms.employee.management.dto.WfhType;
import com.hrms.employee.management.service.WfhBalanceService;
import org.springframework.web.bind.annotation.RequestBody;

import lombok.extern.log4j.Log4j2;


@Log4j2
@RestController
@RequestMapping("/employee/wfh-balance")
@CrossOrigin(origins = "*")
public class WFHBalanceController {

    @Autowired
    private WfhBalanceService wfhBalanceService;

    @GetMapping("/{employeeId}")
    public ResponseEntity<List<WfhBalanceDto>> getEmployeeWfhBalances(@PathVariable String employeeId) {
        log.info("Received request to fetch WFH balances for employeeId: {}", employeeId);
        return ResponseEntity.ok(wfhBalanceService.getEmployeeWfhBalances(employeeId));
    }

    @PostMapping("/initialize/{employeeId}")
    public ResponseEntity<String> initializeWfhBalanceForNewEmployee(@PathVariable String employeeId) {
        log.info("initializeWfhBalanceForNewEmployee called - employeeId={}", employeeId);
        wfhBalanceService.initializeWfhBalanceForNewEmployee(employeeId);
        return ResponseEntity.ok("Wfh balances initialized successfully");
    }

    @PostMapping("/initialize-for-new-wfh-type")
    public ResponseEntity<String> initializeWfhBalanceForNewWfhType(@RequestBody WfhType wfhType) {
        log.info("initializeWfhBalanceForNewWfhType called - wfhType={}", wfhType.getName());
        wfhBalanceService.initializeWfhBalanceForNewWfhType(wfhType);
        return ResponseEntity.ok("Wfh balances initialized for new wfh type");
    }

    @PostMapping("/{employeeId}/deduct/{wfhTrackerId}")
    public void deductWfhBalance(@PathVariable String employeeId,@PathVariable Long wfhTrackerId) {
        log.info("Received request to deduct WFH balance for employeeId: {}, wfhTrackerId: {}", employeeId, wfhTrackerId);
        wfhBalanceService.deductWfhBalance(employeeId, wfhTrackerId);
    }

    //disbursal logic
    @PostMapping("/{employeeId}/disburse/{wfhTrackerId}")
    public void disburseWfhBalance(Long employeeId, Long wfhTrackerId) {
        log.info("Received request to disburse WFH balance for employeeId: {}, wfhTrackerId: {}", employeeId, wfhTrackerId);
        wfhBalanceService.disburseWfhBalance(employeeId, wfhTrackerId);
    }

}