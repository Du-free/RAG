package com.example.ragdemo.security;

/**
 * 当前登录用户的最小身份信息，会放入 Spring SecurityContext 供业务层做权限隔离。
 */
public record AuthenticatedUser(Long id, String username, String role) {

    public boolean admin() {
        return "ADMIN".equals(role);
    }
}
