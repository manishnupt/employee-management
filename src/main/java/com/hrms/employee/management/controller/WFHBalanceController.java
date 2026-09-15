package com.hrms.employee.management.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hrms.employee.management.service.WfhBalanceService;
import org.springframework.web.bind.annotation.RequestBody;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("/employee/wfh-balance")
@Log4j2
public class WFHBalanceController {

    @Autowired
    private WfhBalanceService wfhBalanceService;

    @PostMapping("/{employeeId}/deduct/{wfhTrackerId}")
    public void deductWfhBalance(Long employeeId, Long wfhTrackerId) {
        log.info("deductWfhBalance called for employeeId={} wfhTrackerId={}", employeeId, wfhTrackerId);
        wfhBalanceService.deductWfhBalance(employeeId, wfhTrackerId);
    }
    //disbursal logic
    @PostMapping("/{employeeId}/disburse/{wfhTrackerId}")
    public void disburseWfhBalance(Long employeeId, Long wfhTrackerId) {
        log.info("disburseWfhBalance called for employeeId={} wfhTrackerId={}", employeeId, wfhTrackerId);
        wfhBalanceService.disburseWfhBalance(employeeId, wfhTrackerId);
    }

}
