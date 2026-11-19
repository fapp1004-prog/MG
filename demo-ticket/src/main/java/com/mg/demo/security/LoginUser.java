package com.mg.demo.security;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** 로그인 사용자. 컨트롤러에서 @AuthenticationPrincipal LoginUser user 로 받는다. */
public class LoginUser implements UserDetails {

    private final Long id;
    private final String username;
    private final String password;
    private final String role;
    private final String displayName;

    public LoginUser(Long id, String username, String password, String role, String displayName) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.role = role;
        this.displayName = displayName;
    }

    public Long getId() {
        return id;
    }

    public String getRole() {
        return role;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
