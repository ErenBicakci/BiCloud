package com.bic.cloud.controlplane.security;

import com.bic.cloud.controlplane.model.BicloudUser;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Getter
public class BicloudUserDetails implements UserDetails {

    private final UUID id;
    private final String username;
    private final String password;
    private final String role;
    private final Collection<? extends GrantedAuthority> authorities;

    public BicloudUserDetails(BicloudUser user) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.password = user.getPassword();
        this.role = user.getRole();
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
    }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return true; }
}
