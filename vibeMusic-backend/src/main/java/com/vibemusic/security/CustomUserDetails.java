package com.vibemusic.security;

import com.vibemusic.entity.User;
import lombok.AllArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@AllArgsConstructor
public class CustomUserDetails implements UserDetails {

    private final User user;

    public Long getUserId() {
        return user.getId();
    }

    public String getNickname() {
        return user.getNickname() != null ? user.getNickname() : user.getUsername();
    }

    public String getAvatar() {
        return user.getAvatar();
    }

    public String getBgImage() {
        return user.getBgImage();
    }

    public String getGender() {
        return user.getGender();
    }

    public String getBirthday() {
        return user.getBirthday();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    // Bug9修复: 显式声明账户状态方法（虽然Spring Security 6有默认true实现，但显式声明更安全）
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
        return user.getEnabled();
    }
}
