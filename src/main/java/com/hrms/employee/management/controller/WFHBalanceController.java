package com.hrms.employee.management.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hrms.employee.management.service.WfhBalanceService;
import org.springframework.web.bind.annotation.RequestBody;


@RestController
@RequestMapping("/employee/wfh-balance")
public class WFHBalanceController {

    @Autowired
    private WfhBalanceService wfhBalanceService;

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
