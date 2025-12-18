package com.hrms.employee.management.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.hrms.employee.management.dao.WFHTransaction;

@Repository
public interface WFHTransactionRepository extends JpaRepository<WFHTransaction, Long> {

}
