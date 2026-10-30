package com.moveinsync.distance.persistence;

import com.moveinsync.distance.security.Role;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A login account. Only the bcrypt hash of the password is ever stored. */
@Entity
@Table(name = "app_user")
public class AppUserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    private Role role;

    private String tenantId;
    private String employeeRef;
    private boolean enabled = true;

    protected AppUserEntity() {
        // for JPA
    }

    public AppUserEntity(String username, String passwordHash, Role role, String tenantId, String employeeRef) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.tenantId = tenantId;
        this.employeeRef = employeeRef;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getEmployeeRef() {
        return employeeRef;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
