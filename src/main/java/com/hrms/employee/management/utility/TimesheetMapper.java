package com.hrms.employee.management.utility;

import org.springframework.stereotype.Component;

import com.hrms.employee.management.dao.Timesheet;
import com.hrms.employee.management.dto.TimesheetDto;

@Component
public class TimesheetMapper {

    public TimesheetDto convertToEntity(Timesheet timesheetDto) {
        TimesheetDto timesheet = new TimesheetDto();
        timesheet.setTimesheetId(timesheetDto.getId());
        timesheet.setEmployeeId(timesheetDto.getEmployee().getEmployeeId());
        timesheet.setWorkDate(timesheetDto.getWorkDate());
        timesheet.setClockIn(timesheetDto.getClockIn());
        timesheet.setClockOut(timesheetDto.getClockOut());
        //timesheet.setTotalHours(timesheetDto.getTotalHours());
        return timesheet;
    }

}
