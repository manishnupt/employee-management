package com.hrms.employee.management.service;

import com.hrms.employee.management.utility.AuthorizationUtil;
import org.apache.tomcat.util.http.parser.Authorization;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.hrms.employee.management.dao.LeaveTracker;
import com.hrms.employee.management.dao.Regularization;
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

    public Long createActionItem(String employeeId, Object object, String assignedManagerId) {
        log.info("Creating action item for employeeId: {}, assignedManagerId: {}", employeeId, assignedManagerId);
        if (assignedManagerId == null || assignedManagerId.isEmpty())
            return null;
        String url = utilityBaseUrl + "/action-item";
        log.info("url to create action item :{}",url);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", TenantContext.getCurrentTenant());
        headers.setContentType(MediaType.APPLICATION_JSON);
        ActionItemExtRequest request=null ;
        if (object instanceof LeaveTracker) {
            log.info("Creating leave action item");
            request = ActionItemHelper.convertToLeaveRequest((LeaveTracker)object, employeeId,
                    assignedManagerId);
        }
        else if (object instanceof Timesheet){
            log.info("Creating timesheet action item");
             request = ActionItemHelper.convertToTimesheetequest((Timesheet)object, employeeId,
                    assignedManagerId);
        }
        else if(object instanceof WFHTracker) {

            log.info("Creating WFH action item");
            request = ActionItemHelper.convertToWFHRequest((WFHTracker)object, employeeId,
                    assignedManagerId);
        }
        else if (object instanceof Regularization) {
            log.info("Creating regularization action item");
            request = ActionItemHelper.convertToRegularizationRequest((Regularization)object, employeeId,
                    assignedManagerId);
        }
        else {
            log.info("Unsupported object type for action item creation: {}", object.getClass().getName());
            return null;
        }

        log.info("ex request :{}", request);
        log.info("url :{}", url);
        HttpEntity<ActionItemExtRequest> requestEntity = new HttpEntity<>(request, headers);
        ResponseEntity<Long> createdActionItemId = restTemplate.postForEntity(
                url,
                requestEntity, Long.class);
        return createdActionItemId.getBody();

    }

    /** Removes the manager's action item when the request it was raised for is deleted. */
    public void deleteActionItem(Long actionItemId) {
        if (actionItemId == null)
            return;
        String url = utilityBaseUrl + "/action-item/" + actionItemId;
        log.info("url to delete action item :{}", url);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, AuthorizationUtil.getAuthorizationHeader());
        headers.set("X-Tenant-Id", TenantContext.getCurrentTenant());
        restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
    }

}
