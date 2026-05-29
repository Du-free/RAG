package com.example.ragdemo.service;

import com.example.ragdemo.dto.AuthLoginRequest;
import com.example.ragdemo.dto.AuthUserResponse;
import com.example.ragdemo.security.AuthenticatedUser;
import com.example.ragdemo.security.CurrentUser;
import com.example.ragdemo.security.JwtAuthenticationFilter;
import com.example.ragdemo.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * 登录、退出和当前用户查询服务。密码只和数据库中的 BCrypt 哈希比较，不回传明文。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    private final RowMapper<UserRecord> userRowMapper = (rs, rowNum) -> new UserRecord(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("role"),
            rs.getBoolean("enabled")
    );

    public ResponseEntity<AuthUserResponse> login(AuthLoginRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        String username = requireText(request.username(), "用户名或密码错误");
        String password = requireText(request.password(), "用户名或密码错误");
        UserRecord user = findByUsername(username);
        if (user == null || !user.enabled() || !passwordEncoder.matches(password, user.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }

        AuthenticatedUser authenticatedUser = new AuthenticatedUser(user.id(), user.username(), user.role());
        String token = jwtService.createToken(authenticatedUser);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, tokenCookie(token, jwtService.expireSeconds()).toString())
                .body(toResponse(authenticatedUser));
    }

    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, tokenCookie("", 0).toString())
                .build();
    }

    public AuthUserResponse me() {
        return toResponse(CurrentUser.require());
    }

    private UserRecord findByUsername(String username) {
        var users = jdbcTemplate.query("""
                SELECT id, username, password_hash, role, enabled
                FROM rag_user
                WHERE username = ?
                """, userRowMapper, username);
        return users.isEmpty() ? null : users.get(0);
    }

    private ResponseCookie tokenCookie(String token, long maxAgeSeconds) {
        return ResponseCookie.from(JwtAuthenticationFilter.COOKIE_NAME, token)
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }

    private AuthUserResponse toResponse(AuthenticatedUser user) {
        return new AuthUserResponse(user.id(), user.username(), user.role());
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
        }
        return value.trim();
    }

    private record UserRecord(Long id, String username, String passwordHash, String role, boolean enabled) {
    }
}
