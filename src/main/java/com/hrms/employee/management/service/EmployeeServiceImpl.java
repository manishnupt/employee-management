package com.hrms.employee.management.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.hrms.employee.management.dto.*;
import com.hrms.employee.management.utility.*;
import jakarta.transaction.Transactional;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.LeaveTracker;
import com.hrms.employee.management.dao.Timesheet;
import com.hrms.employee.management.dto.GenerateTokenRequest;
import com.hrms.employee.management.dto.OnboardKeycloakUserRequest;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.LeaveTrackerRepository;
import com.hrms.employee.management.repository.TimesheetRepository;

import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@Log4j2
public class EmployeeServiceImpl implements EmployeeService {

    @Value("${iam_service_base_url}")
    private String iamServiceBaseUrl;

    @Value("${tenant_management_base_url}")
    private String tenantUrl;

    @Value("${admin_base_url}")
    private String adminBaseUrl;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private LeaveBalanceService leaveBalanceService;

    private final EmployeeRepository employeeRepository;
    private final TimesheetRepository timesheetRepository;
    private final LeaveTrackerRepository leaveTrackerRepository;
    private final EmployeeMapper employeeMapper;
    private final TimesheetMapper timesheetMapper;
    private final LeaveTrackerMapper leaveTrackerMapper;

    public EmployeeServiceImpl(EmployeeRepository employeeRepository, EmployeeMapper employeeMapper,
            TimesheetRepository timesheetRepository, LeaveTrackerRepository leaveTrackerRepository,
            TimesheetMapper timesheetMapper, LeaveTrackerMapper leaveTrackerMapper) {
        this.employeeRepository = employeeRepository;
        this.employeeMapper = employeeMapper;
        this.timesheetRepository = timesheetRepository;
        this.leaveTrackerRepository = leaveTrackerRepository;
        this.timesheetMapper = timesheetMapper;
        this.leaveTrackerMapper = leaveTrackerMapper;
    }

    @Override
    public Employee createEmployee(EmployeeDto employeeDto, String userId) {
        Employee employee = employeeMapper.toEntity(employeeDto);
        employee.setEmployeeId(userId);
        Employee savedEmployee = employeeRepository.save(employee);

        try {
            leaveBalanceService.initializeLeaveBalanceForNewEmployee(userId);
        } catch (Exception e) {
            log.error("Failed to initialize leave balances for employee {}: {}", userId, e.getMessage());
        }

        return savedEmployee;
    }

    @Override
    public Employee updateEmployee(String employeeId, EmployeeDto employeeDto) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));
        employeeMapper.updateEntity(employee, employeeDto);
        return employeeRepository.save(employee);
    }

    @Override
    public Employee getEmployeeById(String employeeId) {
        return employeeRepository.findById(employeeId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));
    }

    @Override
    public List<Employee> getAllEmployees() {
        return employeeRepository.findAll();
    }

    @Override
    public EmployeeCountDto getEmployeeCounts() {
        long totalEmployees = employeeRepository.count();
        long activeEmployees = employeeRepository.countByJobStatus("Active");
        return new EmployeeCountDto(totalEmployees, activeEmployees);
    }

    @Override
    public EmployeeReportResponse getEmployeeReportById(String employeeId, int month, int year) {

        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        YearMonth yearMonth = YearMonth.of(year, month);
        int noOfDaysInMonth = yearMonth.lengthOfMonth();
        LocalDate startDate = YearMonth.of(year, month).atDay(1);
        LocalDate endDate = YearMonth.of(year, month).atEndOfMonth();

        List<LeaveTracker> leaves = leaveTrackerRepository.findByEmployeeAndMonth(employeeId, startDate, endDate);
        List<Timesheet> timesheets = timesheetRepository.findByEmployeeAndMonth(employeeId, month, year);

        EmployeeReportResponse reportResponses = EmployeeReportResponse.builder()
                .employeeId(employee.getEmployeeId())
                .employeeName(employee.getName())
                .year(year)
                .month(month)
                .monthName(yearMonth.getMonth().name())
                .build();

        List<String> weekends = new ArrayList<>();
        weekends.add("SATURDAY");
        weekends.add("SUNDAY");

        int weekendCount = 0;
        List<WorkDaysDto> workDays = new ArrayList<>();
        for (int i = 1; i <= noOfDaysInMonth; i++) {
            WorkDaysDto workDay = new WorkDaysDto();
            LocalDate currentDate = startDate.plusDays(i - 1);
            String dayOfWeek = startDate.plusDays(i - 1).getDayOfWeek().name();
            workDay.setDate(currentDate.toString());
            workDay.setDayOfWeek(dayOfWeek);

            if (hasTimesheetOnDate(currentDate, timesheets)) {
                workDay.setStatus("PRESENT");
                workDay.setTimesheetDto(getTimesheetDto(currentDate, timesheets));
                workDay.setLeaveTrackerDto(null);
            } else if (weekends.contains(dayOfWeek)) {
                workDay.setStatus("WEEKEND");
                workDay.setLeaveTrackerDto(null);
                workDay.setTimesheetDto(null);
                weekendCount++;
            }
            else if (hasLeaveOnDate(currentDate, leaves)) {
                workDay.setStatus("ON_Leave");
                workDay.setTimesheetDto(null);
                workDay.setLeaveTrackerDto(getLeaveTrackerDto(currentDate, leaves));

            } else {
                workDay.setTimesheetDto(getEmptyTimesheetDto(employeeId, currentDate));
                workDay.setStatus("UNFILLED");
                workDay.setLeaveTrackerDto(null);

            }
            workDays.add(workDay);
        }
        reportResponses.setWorkDays(workDays);
        reportResponses.setSummary(getEmployeeSummary(workDays, noOfDaysInMonth, weekendCount));

        return reportResponses;
    }

    @Override
    public String onboardUserInKeycloak(EmployeeDto employeeDto, String currentTenant) {

        Map<String,Object> masterRealmDetails =getMasterRealmDetails();
        String token=getKeycloakToken(masterRealmDetails);
        log.info("token:{}",token);
        String url = iamServiceBaseUrl + Constants.ONBOARD_KEYCLOAK_USER;
        OnboardKeycloakUserRequest userRequest = EmployeeOnboardingHelper.getOnboardKeycloakUserRequest(employeeDto,TenantContext.getCurrentTenant());
        HttpEntity<OnboardKeycloakUserRequest> entity = new HttpEntity<>(userRequest, createHeaders(token));

        ResponseEntity<Map<String, String>> response = makeApiCall(url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {
                },
                ErrorCodes.KEYCLOAK_USER_ONBOARD_ERROR, "Failed to onboard Keycloak user");

        Map<String, String> responseBody = response.getBody();
        if (responseBody == null || !responseBody.containsKey("userId")) {
            throw new KeycloakException(ErrorCodes.KEYCLOAK_USER_ONBOARD_ERROR, "Missing user ID in response");
        }
        return responseBody.get("userId");
    }



    private HttpHeaders createHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private <T> ResponseEntity<T> makeApiCall(String url, HttpMethod method, HttpEntity<?> entity, ParameterizedTypeReference<T> responseType, int errorCode, String errorMessage) {
        try {
            return restTemplate.exchange(url, method, entity, responseType);
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("{} - Status: {}, Response: {}", errorMessage, e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new RuntimeException(errorMessage + " - Status: " + e.getStatusCode(), e);
        }
    }

    public String getKeycloakToken(Map<String,Object> masterRealmDetails) {
        String adminAccessTokenUrl = iamServiceBaseUrl + Constants.GET_ADMIN_TOKEN;
        GenerateTokenRequest tokenRequest = EmployeeMapper.getGenerateTokenRequest(masterRealmDetails);
        HttpEntity<GenerateTokenRequest> entity = new HttpEntity<>(tokenRequest, createHeaders(null));

        ResponseEntity<Map<String, String>> response = makeApiCall(adminAccessTokenUrl, HttpMethod.POST, entity,
                new ParameterizedTypeReference<>() {}, 500, "Failed to obtain admin access token");
        log.info("fetched the admin token succesfully");

        return response.getBody().get("token");
    }


    private Map<String, Object> getMasterRealmDetails() {
        String url = tenantUrl +"/api/v1/tenants/auth-config/" + "master";
        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
        Map<String, Object> responseBody = response.getBody();
        return (Map<String, Object>) responseBody.get("data");
    }

    public List<Employee> findUnassignedEmployees() {
        return employeeRepository.findByGroupIdIsNull();
    }

    public List<Employee> findEmployeesByGroup(Long groupId) {
        return employeeRepository.findByGroupId(groupId);
    }

    @Override
    @Transactional
    public void assignGroupToEmployee(String token,String employeeId, Long groupId) {
        Employee emp= employeeRepository.findById(employeeId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));
        if(emp.getGroupId()!=null){
            throw new RuntimeException("Group already present");
        }
        emp.setGroupId(groupId);
        employeeRepository.save(emp);
        log.info("assigned group to user");
        RoleGroupExtResponse groupById = getGroupById(groupId);
        log.info("retreived data of group:{}",groupById);
        Map<String,Object> masterRealmDetails =getMasterRealmDetails();
        String kcToken=getKeycloakToken(masterRealmDetails);
        grantAdminAccess(kcToken,employeeId,TenantContext.getCurrentTenant(),groupById.getKcGroupIdRef());
    }
    @Override
    @Transactional
    public void unassignGroupFromEmployee(String token, String employeeId) {
        Employee emp= employeeRepository.findById(employeeId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));
        if(emp.getGroupId()==null){
            throw new RuntimeException("Group not assigned to this employee");
        }
        Long groupId=emp.getGroupId();
        RoleGroupExtResponse groupById = getGroupById(groupId);
        log.info("retreived data of group:{}",groupById);
        Map<String,Object> masterRealmDetails =getMasterRealmDetails();
        String kcToken=getKeycloakToken(masterRealmDetails);
        removeUserFromGroup(kcToken,employeeId,TenantContext.getCurrentTenant(),groupById.getKcGroupIdRef());
        emp.setGroupId(null);
        employeeRepository.save(emp);
    }

    public RoleGroupExtResponse getGroupById(Long groupId) {

        log.info("trying to get the group details");
        String url = adminBaseUrl + "/admin/group/" + groupId;
        log.info("url decoded :{}",url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tenant-Id", TenantContext.getCurrentTenant());
        // Add auth header if required
        // headers.set("Authorization", "Bearer YOUR_TOKEN");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<RoleGroupExtResponse> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                RoleGroupExtResponse.class
        );

        return response.getBody();
    }

    public void grantAdminAccess(String token, String userId, String realmName,String groupId) {
        log.info("inside grantAdmin aceess with token :{}",token);
        String url = iamServiceBaseUrl + Constants.GRANT_ADMIN_ACCESS;
        log.info("trying to get assign the group to the user in keycloak, with url :{} and the groupId is :{}",url,groupId);

        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("realmName", realmName)
                .queryParam("userId", userId)
                .queryParam("groupId",groupId);

        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(token));

        makeApiCall(uriBuilder.toUriString(), HttpMethod.GET, entity, Void.class,
                ErrorCodes.KEYCLOAK_ADMIN_ACCESS_ERROR, "Failed to grant admin access");
    }
    public void removeUserFromGroup(String token, String userId, String realmName,String groupId) {
        log.info("inside removeUserFromGroup where token is{}",token);
        String url = iamServiceBaseUrl + Constants.REMOVE_GROUP_ACCESS;
        log.info("trying to get remove the group to the user in keycloak, with url :{} and the groupId is :{}",url,groupId);

        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("realmName", realmName)
                .queryParam("userId", userId)
                .queryParam("groupId",groupId);

        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(token));

        makeApiCall(uriBuilder.toUriString(), HttpMethod.DELETE, entity, Void.class,
                ErrorCodes.KEYCLOAK_ADMIN_ACCESS_ERROR, "Failed to revoke admin access");
    }
    private <T> ResponseEntity<T> makeApiCall(String url, HttpMethod method, HttpEntity<?> entity, Class<T> responseType, int errorCode, String errorMessage) {
        try {
            return restTemplate.exchange(url, method, entity, responseType);
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("{} - Status: {}, Response: {}", errorMessage, e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new KeycloakException(errorCode, errorMessage + " - Status: " + e.getStatusCode(), e);
        }
    }

    @Override
    public void assignManagerToEmployee(String employeeId, String managerEmpId) {
        Employee emp= employeeRepository.findById(employeeId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));
        if(emp.getAssignedManagerId()!=null){
            throw new RuntimeException("Manager already present");
        }
        emp.setAssignedManagerId(managerEmpId);
        employeeRepository.save(emp);
    }


    @Override
    public void unassignManagerToEmployee(String employeeId) {
        Employee emp= employeeRepository.findById(employeeId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));
        if(emp.getAssignedManagerId()==null ){
            throw new RuntimeException("Manager not assigned to this employee");
        }
        emp.setAssignedManagerId(null);
        employeeRepository.save(emp);
    }

    @Override
    public Employee findEmployeesByKcRefId(String kcRefId) {
        return employeeRepository.findById(kcRefId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));
    }

    public SummaryDto getEmployeeSummary(List<WorkDaysDto> workDaysDto, int noOfDaysInMonth, int weekendCount) {

        int totalWorkingDays = noOfDaysInMonth - weekendCount;
        Double workingHour = 9.00;
        Double totalWorkingHours = totalWorkingDays * workingHour;

        SummaryDto summaryDto = SummaryDto.builder()
                .totalWorkDays(totalWorkingDays)
                .totalPresentDays((int) workDaysDto.stream()
                        .filter(wd -> "PRESENT".equals(wd.getStatus())).count())
                .totalAbsentDays((int) workDaysDto.stream()
                        .filter(wd -> "UNFILLED".equals(wd.getStatus())).count())
                .totalLeaveDays((int) workDaysDto.stream()
                        .filter(wd -> "ON_Leave".equals(wd.getStatus())).count())
                .totalWfhDays(0)
                .totalWorkingHours(totalWorkingHours)
                .totalRegularHours(workDaysDto.stream()
                        .filter(wd -> wd.getTimesheetDto() != null)
                        .mapToDouble(wd -> wd.getTimesheetDto().getTotalHours()).sum())
                .totalOvertimeHours(totalWorkingHours - workDaysDto.stream()
                        .filter(wd -> wd.getTimesheetDto() != null)
                        .mapToDouble(wd -> wd.getTimesheetDto().getTotalHours()).sum())
                .build();

        return summaryDto;
    }

    public TimesheetDto getEmptyTimesheetDto(String employeeId, LocalDate currentDate) {
        TimesheetDto emptyTimesheet = new TimesheetDto();
        emptyTimesheet.setWorkDate(currentDate);
        emptyTimesheet.setClockIn(LocalTime.parse("00:00:00"));
        emptyTimesheet.setClockOut(LocalTime.parse("00:00:00"));
        emptyTimesheet.setTotalHours(0.0);
        emptyTimesheet.setEmployeeId(employeeId);
        return emptyTimesheet;
    }

    public boolean hasTimesheetOnDate(LocalDate date, List<Timesheet> timesheets) {
        return timesheets.stream().anyMatch(ts -> ts.getWorkDate().isEqual(date));
    }

    public TimesheetDto getTimesheetDto(LocalDate date, List<Timesheet> timesheets) {
        Timesheet timesheet = timesheets.stream()
                        .filter(ts -> ts.getWorkDate().isEqual(date))
                        .findFirst()
                        .orElse(null);
        
        return timesheetMapper.convertToEntity(timesheet);
    }

    public boolean hasLeaveOnDate(LocalDate date, List<LeaveTracker> leaves) {
        return leaves.stream().anyMatch(lt -> (lt.getStartDate().isEqual(date) || lt.getStartDate().isBefore(date))
                && (lt.getEndDate().isEqual(date) || lt.getEndDate().isAfter(date)));
    }

    public LeaveTrackerDto getLeaveTrackerDto(LocalDate date,List<LeaveTracker> leaves) {
        LeaveTracker leave = leaves.stream()
                        .filter(lt -> (lt.getStartDate().isEqual(date)
                                || lt.getStartDate().isBefore(date)) &&
                                (lt.getEndDate().isEqual(date) || lt.getEndDate().isAfter(date)))
                        .findFirst()
                        .orElse(null);
        
        return leaveTrackerMapper.convertToDto(leave);
    }

}