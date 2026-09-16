package com.hrms.employee.management.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hrms.employee.management.dto.WfhBalanceDto;
import com.hrms.employee.management.service.WfhBalanceService;
import org.springframework.web.bind.annotation.RequestBody;


@RestController
@RequestMapping("/employee/wfh-balance")
public class WFHBalanceController {

    @Autowired
    private WfhBalanceService wfhBalanceService;

    @GetMapping("/{employeeId}")
    public ResponseEntity<List<WfhBalanceDto>> getEmployeeWfhBalances(@PathVariable String employeeId) {
        return ResponseEntity.ok(wfhBalanceService.getEmployeeWfhBalances(employeeId));
    }

    @PostMapping("/{employeeId}/deduct/{wfhTrackerId}")
    public void deductWfhBalance(Long employeeId, Long wfhTrackerId) {
        wfhBalanceService.deductWfhBalance(employeeId, wfhTrackerId);
    }
    //disbursal logic
    @PostMapping("/{employeeId}/disburse/{wfhTrackerId}")
    public void disburseWfhBalance(Long employeeId, Long wfhTrackerId) {
        wfhBalanceService.disburseWfhBalance(employeeId, wfhTrackerId);
    }


}
