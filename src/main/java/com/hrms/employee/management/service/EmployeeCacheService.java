package com.hrms.employee.management.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dto.CachedEmployee;
import com.hrms.employee.management.dto.RoleGroupExtResponse;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.utility.TenantContext;

import lombok.extern.log4j.Log4j2;

/**
 * Keeps all employees of a tenant in one Redis hash so that reads (search, get-all, get-by-id ...)
 * don't hit the DB.
 *
 * Layout:
 *   hrms:emp:{tenant}:employees  -> hash of employeeId -> CachedEmployee JSON, plus a "__loaded__" marker field
 *   hrms:emp:{tenant}:group:{id} -> RoleGroupExtResponse JSON (from the admin service), short TTL
 *
 * The first read for a tenant loads every employee from the DB (HSETNX, so it never overwrites an entry
 * written by a concurrent update). Writes go through {@link #put(Employee)} after the DB commit.
 * Any Redis failure falls back to the DB.
 */
@Service
@Log4j2
public class EmployeeCacheService {

    private static final String LOADED_MARKER = "__loaded__";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final EmployeeRepository employeeRepository;

    @Value("${employee.cache.enabled:true}")
    private boolean enabled;

    @Value("${employee.cache.ttl-hours:12}")
    private long ttlHours;

    @Value("${employee.cache.group-ttl-minutes:30}")
    private long groupTtlMinutes;

    public EmployeeCacheService(StringRedisTemplate redis, ObjectMapper objectMapper,
            EmployeeRepository employeeRepository) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.employeeRepository = employeeRepository;
    }

    /** All employees of the current tenant, including soft-deleted ones (same as employeeRepository.findAll()). */
    public List<Employee> getAll() {
        String tenant = currentTenant();
        if (tenant == null) {
            return employeeRepository.findAll();
        }
        String key = employeesKey(tenant);
        try {
            Map<Object, Object> entries = redis.opsForHash().entries(key);
            if (entries.containsKey(LOADED_MARKER)) {
                List<Employee> result = new ArrayList<>(entries.size());
                for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                    if (!LOADED_MARKER.equals(entry.getKey())) {
                        result.add(fromJson((String) entry.getValue()));
                    }
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("Employee cache read failed for tenant {}, using DB: {}", tenant, e.getMessage());
            return employeeRepository.findAll();
        }

        log.info("Employee cache miss for tenant {}, loading from DB", tenant);
        List<Employee> fromDb = employeeRepository.findAll();
        populate(key, fromDb);
        return fromDb;
    }

    /** Same semantics as employeeRepository.findById (soft-deleted employees are returned too). */
    public Optional<Employee> findById(String employeeId) {
        if (employeeId == null) {
            return Optional.empty();
        }
        String tenant = currentTenant();
        if (tenant == null) {
            return employeeRepository.findById(employeeId);
        }
        try {
            List<Object> values = redis.opsForHash().multiGet(employeesKey(tenant), List.of(employeeId, LOADED_MARKER));
            if (values.get(0) != null) {
                return Optional.of(fromJson((String) values.get(0)));
            }
            if (values.get(1) != null) {
                // Cache is fully loaded and doesn't have this id, so it doesn't exist.
                return Optional.empty();
            }
        } catch (Exception e) {
            log.warn("Employee cache read failed for tenant {}, using DB: {}", tenant, e.getMessage());
            return employeeRepository.findById(employeeId);
        }
        // Not loaded yet: load the whole tenant once, later reads are served from Redis.
        return getAll().stream().filter(emp -> employeeId.equals(emp.getEmployeeId())).findFirst();
    }

    /** Writes the employee into the cache once the current DB transaction commits (or right away if none). */
    public void put(Employee employee) {
        String tenant = currentTenant();
        if (tenant == null || employee == null || employee.getEmployeeId() == null) {
            return;
        }
        String json = toJson(employee);
        runAfterCommit(() -> {
            try {
                redis.opsForHash().put(employeesKey(tenant), employee.getEmployeeId(), json);
            } catch (Exception e) {
                // A stale entry is worse than a cold cache, so drop the tenant's cache.
                log.warn("Employee cache write failed for tenant {}, evicting: {}", tenant, e.getMessage());
                evictTenant(tenant);
            }
        });
    }

    public void evictTenant(String tenant) {
        try {
            redis.delete(employeesKey(tenant));
        } catch (Exception e) {
            log.error("Failed to evict employee cache for tenant {}: {}", tenant, e.getMessage());
        }
    }

    /** Group details from the admin service, cached for a short time. */
    public RoleGroupExtResponse getGroup(Long groupId, Supplier<RoleGroupExtResponse> remoteLoader) {
        String tenant = currentTenant();
        if (tenant == null || groupId == null) {
            return remoteLoader.get();
        }
        String key = "hrms:emp:" + tenant + ":group:" + groupId;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, RoleGroupExtResponse.class);
            }
        } catch (Exception e) {
            log.warn("Group cache read failed for tenant {}: {}", tenant, e.getMessage());
            return remoteLoader.get();
        }
        RoleGroupExtResponse group = remoteLoader.get();
        if (group != null) {
            try {
                redis.opsForValue().set(key, objectMapper.writeValueAsString(group), Duration.ofMinutes(groupTtlMinutes));
            } catch (Exception e) {
                log.warn("Group cache write failed for tenant {}: {}", tenant, e.getMessage());
            }
        }
        return group;
    }

    /** Stable order for in-memory paging: name (case-insensitive, nulls last), then id. */
    public static final Comparator<Employee> DEFAULT_ORDER = Comparator
            .comparing(Employee::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(Employee::getEmployeeId, Comparator.nullsLast(Comparator.naturalOrder()));

    private void populate(String key, List<Employee> employees) {
        try {
            byte[] rawKey = key.getBytes(StandardCharsets.UTF_8);
            List<byte[][]> fields = employees.stream()
                    .filter(e -> e.getEmployeeId() != null)
                    .map(e -> new byte[][] { bytes(e.getEmployeeId()), bytes(toJson(e)) })
                    .toList();
            redis.executePipelined((RedisCallback<Object>) (RedisConnection conn) -> {
                // HSETNX: never overwrite a newer value written by a concurrent update.
                for (byte[][] f : fields) {
                    conn.hashCommands().hSetNX(rawKey, f[0], f[1]);
                }
                conn.hashCommands().hSet(rawKey, bytes(LOADED_MARKER), bytes("1"));
                conn.keyCommands().expire(rawKey, Duration.ofHours(ttlHours).toSeconds());
                return null;
            });
        } catch (Exception e) {
            log.warn("Failed to populate employee cache {}: {}", key, e.getMessage());
        }
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private String currentTenant() {
        if (!enabled) {
            return null;
        }
        String tenant = TenantContext.getCurrentTenant();
        return (tenant == null || tenant.isBlank()) ? null : tenant;
    }

    private static String employeesKey(String tenant) {
        return "hrms:emp:" + tenant + ":employees";
    }

    private static byte[] bytes(String s) {
        return Objects.requireNonNull(s).getBytes(StandardCharsets.UTF_8);
    }

    private String toJson(Employee employee) {
        try {
            return objectMapper.writeValueAsString(CachedEmployee.from(employee));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize employee " + employee.getEmployeeId(), e);
        }
    }

    private Employee fromJson(String json) {
        try {
            return objectMapper.readValue(json, CachedEmployee.class).toEmployee();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize cached employee", e);
        }
    }
}
