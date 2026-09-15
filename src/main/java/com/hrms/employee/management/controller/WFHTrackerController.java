package com.hrms.employee.management.controller;

import java.time.LocalDate;
import java.util.List;

import com.hrms.employee.management.dto.TimesheetDto;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hrms.employee.management.dto.WFHTrackerRequest;
import com.hrms.employee.management.dao.WFHTracker;
import com.hrms.employee.management.service.WFHService;
import org.springframework.web.bind.annotation.RequestParam;
import com.hrms.employee.management.dto.WFHTrackerResponse;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("/employees/{employeeId}/wfh")
@CrossOrigin(origins ="*")
@Log4j2
public class WFHTrackerController {

    private final WFHService wfhService;

    public WFHTrackerController(WFHService wfhService) {
        this.wfhService = wfhService;
    }

    @PostMapping
    public ResponseEntity<WFHTracker> applyWFH(@PathVariable String employeeId, @RequestBody WFHTrackerRequest wfmTrackerDto) {
        log.info("applyWFH called for employeeId={}", employeeId);
        WFHTracker wfhTracker = wfhService.applyWFH(employeeId, wfmTrackerDto);
        return ResponseEntity.ok(wfhTracker);
    }
    @GetMapping("/{id}")
    public ResponseEntity<WFHTracker> getWFHDetailsById(@PathVariable String employeeId, @PathVariable Long id) {
        log.info("getWFHDetailsById called for employeeId={} id={}", employeeId, id);
        WFHTracker wfhTracker = wfhService.getWFHDetailsById(employeeId, id);
        return ResponseEntity.ok(wfhTracker);
    }

    @GetMapping
    public ResponseEntity<List<WFHTracker>> getWFHHistory(@PathVariable String employeeId) {
        log.info("getWFHHistory called for employeeId={}", employeeId);
        List<WFHTracker> wfhTrackers = wfhService.getWFHHistory(employeeId);
        return ResponseEntity.ok(wfhTrackers);
    }
    @GetMapping("/date")
    public ResponseEntity<WFHTracker> getWFHByDate(@PathVariable String employeeId, @RequestParam LocalDate date) {
        log.info("getWFHByDate called for employeeId={} date={}", employeeId, date);
        WFHTracker wfhTracker = wfhService.getWFHByDate(employeeId,date);
        return ResponseEntity.ok(wfhTracker);
    }

    @GetMapping("/reports/history")
    public ResponseEntity<List<WFHTrackerResponse>> getTimesheetHistory(@PathVariable String employeeId,
                                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate){
        log.info("getWfhReportHistory called for employeeId={} startDate={} endDate={}", employeeId, startDate, endDate);
        List<WFHTrackerResponse> wfhResponse = wfhService.getWfhReportByEmployeeId(employeeId,startDate,endDate);
        return ResponseEntity.ok(wfhResponse);
    }
}
