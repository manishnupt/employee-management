package com.hrms.employee.management.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.hrms.employee.management.dao.LeaveTracker;
import com.hrms.employee.management.dao.Timesheet;
import com.hrms.employee.management.dao.WFHTracker;
import com.hrms.employee.management.dto.ActionItemExtRequest;
import com.hrms.employee.management.utility.ActionItemHelper;
import com.hrms.employee.management.utility.TenantContext;

import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
public class ActionItemService {

    @Autowired
    RestTemplate restTemplate;

    @Value("${utility_base_url}")
    private String utilityBaseUrl;

    public void createActionItem(String employeeId, Object object, String assignedManagerId) {
        if (assignedManagerId == null || assignedManagerId.isEmpty())
            return;
        String url = utilityBaseUrl + "/action-item";
        log.info("url to create action item :{}",url);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", TenantContext.getCurrentTenant());
        headers.setContentType(MediaType.APPLICATION_JSON);
        ActionItemExtRequest request=null ;
        if (object instanceof LeaveTracker) {
            request = ActionItemHelper.convertToLeaveRequest((LeaveTracker)object, employeeId,
                    assignedManagerId);
        }
        else if (object instanceof Timesheet){
             request = ActionItemHelper.convertToTimesheetequest((Timesheet)object, employeeId,
                    assignedManagerId);
        }
        else if(object instanceof WFHTracker) {
            log.info("Creating WFH action item");
            request = ActionItemHelper.convertToWFHRequest((WFHTracker)object, employeeId,
                    assignedManagerId);
        }
        else {
            log.error("Unsupported object type for action item creation: {}", object.getClass().getName());
            return;
        }

        log.info("ex request :{}", request);
        log.info("url :{}", url);
        HttpEntity<ActionItemExtRequest> requestEntity = new HttpEntity<>(request, headers);
        restTemplate.postForEntity(
                url,
                requestEntity, Void.class);

    }



}
