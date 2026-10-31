package com.moveinsync.distance.security;

import com.moveinsync.distance.persistence.AppUserEntity;
import com.moveinsync.distance.persistence.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates demo accounts at start-up so the API can be tried straight away: for acme and
 * globex, one admin, one operator, one employee. DEMO ONLY, turn it off with
 * app.seed-demo-users=false. Existing accounts are left alone, so a restart never resets a
 * password.
 */
@Component
public class DemoUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String password;

    public DemoUserSeeder(AppUserRepository users, PasswordEncoder passwordEncoder,
                          @Value("${app.seed-demo-users:false}") boolean enabled,
                          @Value("${app.demo-password:demo-pass}") String password) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        int created = 0;
        for (String tenant : new String[] {"acme", "globex"}) {
            created += create(tenant + "-admin", Role.ADMIN, tenant, null);
            created += create(tenant + "-operator", Role.OPERATOR, tenant, null);
            created += create(tenant + "-employee", Role.EMPLOYEE, tenant, "E1");
        }
        log.info("demo accounts ready, newly created: {}", created);
    }

    private int create(String username, Role role, String tenant, String employeeRef) {
        if (users.findByUsername(username).isPresent()) {
            return 0;
        }
        try {
            users.saveAndFlush(new AppUserEntity(username, passwordEncoder.encode(password), role, tenant, employeeRef));
            return 1;
        } catch (DataIntegrityViolationException e) {
            return 0; // another instance got there first
        }
    }
}
