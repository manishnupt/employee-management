package com.hrms.employee.management.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.WFHTracker;
import com.hrms.employee.management.exceptions.BusinessException;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.WFHTrackerRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WFHSeriveImplTest {

    private static final String EMPLOYEE_ID = "e1";
    private static final Long WFH_ID = 11L;

    @Mock private WFHTrackerRepository wfhRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ActionItemService actionItemService;

    private WFHSeriveImpl service;
    private WFHTracker wfh;

    @BeforeEach
    void setUp() {
        service = new WFHSeriveImpl(wfhRepository, employeeRepository, actionItemService);

        Employee employee = new Employee();
        employee.setEmployeeId(EMPLOYEE_ID);
        wfh = new WFHTracker();
        wfh.setEmployee(employee);
        wfh.setStatus("PENDING");
        when(wfhRepository.findById(WFH_ID)).thenReturn(Optional.of(wfh));
    }

    @Test
    void deletingPendingWfhRemovesIt() {
        wfh.setLinkedActionItemId(99L);

        service.deleteWFH(EMPLOYEE_ID, WFH_ID);

        verify(wfhRepository).delete(wfh);
        verify(actionItemService).deleteActionItem(99L);
    }

    @Test
    void deletingApprovedWfhIsRejected() {
        wfh.setStatus("APPROVED");

        assertThrows(BusinessException.class, () -> service.deleteWFH(EMPLOYEE_ID, WFH_ID));
        verify(wfhRepository, never()).delete(any());
        verify(actionItemService, never()).deleteActionItem(any());
    }

    @Test
    void deletingAnotherEmployeesWfhIsRejected() {
        assertThrows(BusinessException.class, () -> service.deleteWFH("someone-else", WFH_ID));
        verify(wfhRepository, never()).delete(any());
    }
}
