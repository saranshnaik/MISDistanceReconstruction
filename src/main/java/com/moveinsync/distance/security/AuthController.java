package com.moveinsync.distance.security;

import com.moveinsync.distance.persistence.AppUserEntity;
import com.moveinsync.distance.persistence.AppUserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Username + password in, signed token out. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * A real bcrypt hash of nothing in particular. For an unknown username we still check the
     * password against it, so "unknown user" and "wrong password" take about the same time and
     * give the same answer.
     */
    private final String decoyHash;

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(AppUserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.decoyHash = passwordEncoder.encode("decoy-password-never-used");
    }

    public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 200) String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds, String role) {
    }

    @PostMapping("/token")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        AppUserEntity user = users.findByUsername(request.username()).orElse(null);
        String hash = user == null ? decoyHash : user.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        if (user == null || !user.isEnabled() || !passwordMatches) {
            throw new InvalidCredentialsException();
        }
        JwtService.IssuedToken token = jwtService.issue(user);
        return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds(), user.getRole().name());
    }
}
