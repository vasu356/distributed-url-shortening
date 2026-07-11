package com.urlshortener.infrastructure.security;

import com.urlshortener.domain.model.User;
import com.urlshortener.domain.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Security {@link UserDetailsService} backed by the user repository.
 *
 * <p>Used only during form-login / DAO authentication. JWT authentication bypasses this service
 * entirely — the JWT filter sets the security context directly from token claims.
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

  private final UserRepository userRepository;

  @Override
  @Transactional(readOnly = true)
  public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
    User user =
        userRepository
            .findByEmail(email.toLowerCase().trim())
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

    return org.springframework.security.core.userdetails.User.builder()
        .username(user.getEmail())
        .password(user.getPasswordHash())
        .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())))
        .accountExpired(false)
        .accountLocked(!user.isActive())
        .credentialsExpired(false)
        .disabled(!user.isActive())
        .build();
  }
}
