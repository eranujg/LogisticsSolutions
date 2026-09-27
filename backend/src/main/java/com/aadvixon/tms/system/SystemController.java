package com.aadvixon.tms.system;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
class SystemController {

    private final JdbcTemplate jdbc;

    SystemController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/info")
    Map<String, Object> info() {
        String dbTime = jdbc.queryForObject("SELECT now()::text", String.class);
        Integer tenants = jdbc.queryForObject("SELECT count(*) FROM tenant", Integer.class);
        return Map.of("app", "tms-backend", "databaseTime", dbTime, "tenantCount", tenants);
    }
}
