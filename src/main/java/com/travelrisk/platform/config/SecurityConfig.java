package com.travelrisk.platform.config;

import com.travelrisk.platform.ratelimit.RateLimitFilter;
import com.travelrisk.platform.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ObjectProvider<ClientRegistrationRepository> clientRegistrations,
      JwtAuthenticationFilter jwtAuthenticationFilter,
      RateLimitFilter rateLimitFilter) throws Exception {
    http
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/health", "/login", "/login.html", "/styles.css", "/api/auth/**", "/oauth2/**", "/login/oauth2/**").permitAll()
            .requestMatchers("/api/admin/**").hasRole("ADMIN")
            .anyRequest().authenticated())
        .formLogin(login -> login
            .loginPage("/login")
            .defaultSuccessUrl("/", true)
            .permitAll())
        .logout(logout -> logout
            .logoutSuccessUrl("/login?logout")
            .permitAll())
        .exceptionHandling(exception -> exception
            .authenticationEntryPoint((request, response, authException) -> {
              if (request.getRequestURI().startsWith("/api/")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
              }
              new LoginUrlAuthenticationEntryPoint("/login").commence(request, response, authException);
            })
            .accessDeniedHandler((request, response, accessDeniedException) -> {
              if (request.getRequestURI().startsWith("/api/")) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
              }
              response.sendRedirect("/login");
            }))
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);

    if (clientRegistrations.getIfAvailable() != null) {
      http.oauth2Login(oauth2 -> oauth2
          .loginPage("/login")
          .defaultSuccessUrl("/", true));
    }

    return http.build();
  }

  @Bean
  UserDetailsService userDetailsService(
      PasswordEncoder passwordEncoder,
      @Value("${travel-risk.security.employee-username-pattern}") String employeeUsernamePattern,
      @Value("${travel-risk.security.password}") String password,
      @Value("${travel-risk.security.admin-username:}") String adminUsername,
      @Value("${travel-risk.security.admin-password:}") String adminPassword) {
    Pattern employeePattern = Pattern.compile(employeeUsernamePattern, Pattern.CASE_INSENSITIVE);
    String encodedEmployeePassword = passwordEncoder.encode(password);
    String encodedAdminPassword = adminPassword.isBlank() ? "" : passwordEncoder.encode(adminPassword);
    return username -> {
      if (!adminUsername.isBlank() && !adminPassword.isBlank() && adminUsername.equalsIgnoreCase(username)) {
        return User.withUsername(adminUsername)
            .password(encodedAdminPassword)
            .roles("USER", "ADMIN")
            .build();
      }
      if (employeePattern.matcher(username).matches()) {
        return User.withUsername(username)
            .password(encodedEmployeePassword)
            .roles("USER")
            .build();
      }
      throw new UsernameNotFoundException("User not found: " + username);
    };
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
    return configuration.getAuthenticationManager();
  }

  @Bean
  JwtEncoder jwtEncoder(@Value("${travel-risk.jwt.secret}") String secret) {
    return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(jwtSecret(secret)));
  }

  @Bean
  JwtDecoder jwtDecoder(@Value("${travel-risk.jwt.secret}") String secret) {
    return NimbusJwtDecoder.withSecretKey(jwtSecret(secret)).build();
  }

  private SecretKey jwtSecret(String secret) {
    byte[] bytes = secret.getBytes();
    if (bytes.length < 32) {
      throw new IllegalArgumentException("travel-risk.jwt.secret must be at least 32 bytes");
    }
    return new SecretKeySpec(bytes, "HmacSHA256");
  }
}
