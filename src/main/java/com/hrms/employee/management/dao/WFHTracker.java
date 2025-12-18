package com.hrms.employee.management.dao;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "wfh_tracker")
public class WFHTracker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "employee_id", nullable = false)
    @JsonIgnoreProperties("wfhTrackers")
    private Employee employee;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate startDate;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate endDate;
    private String wfhCreditOption;
    private String status;
    private String reason;

    private LocalDate createdDate;
    private LocalDate updatedDate;

    @PrePersist
    public void prePersist() {
        this.createdDate = LocalDate.now();
    }

    @PreUpdate
    public void postPersist() {
        this.updatedDate = LocalDate.now();
    }

}
