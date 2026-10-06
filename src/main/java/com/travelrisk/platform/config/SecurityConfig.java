package com.travelrisk.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelrisk.platform.demo.DemoAccounts;
import com.travelrisk.platform.ratelimit.RateLimitFilter;
import com.travelrisk.platform.security.JwtAuthenticationFilter;
import com.travelrisk.platform.service.UserProfileService;
import com.travelrisk.platform.web.ApiErrorWriter;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ObjectProvider<ClientRegistrationRepository> clientRegistrations,
      JwtAuthenticationFilter jwtAuthenticationFilter,
      RateLimitFilter rateLimitFilter,
      ObjectProvider<UserProfileService> userProfiles,
      ObjectMapper objectMapper) throws Exception {
    ApiErrorWriter errorWriter = new ApiErrorWriter(objectMapper);
    http
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/health", "/login", "/login.html", "/styles.css", "/api/auth/**", "/oauth2/**", "/login/oauth2/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/admin/dashboard").hasAnyRole("ADMIN", "DEMO_ADMIN")
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
                errorWriter.write(response, HttpStatus.UNAUTHORIZED, "Sign in to access this resource.");
                return;
              }
              new LoginUrlAuthenticationEntryPoint("/login").commence(request, response, authException);
            })
            .accessDeniedHandler((request, response, accessDeniedException) -> {
              if (request.getRequestURI().startsWith("/api/")) {
                errorWriter.write(response, HttpStatus.FORBIDDEN, "You do not have permission to access this resource.");
                return;
              }
              response.sendRedirect("/login");
            }))
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);

    if (clientRegistrations.getIfAvailable() != null) {
      SavedRequestAwareAuthenticationSuccessHandler toHome = new SavedRequestAwareAuthenticationSuccessHandler();
      toHome.setDefaultTargetUrl("/");
      toHome.setAlwaysUseDefaultTargetUrl(true);
      http.oauth2Login(oauth2 -> oauth2
          .loginPage("/login")
          .successHandler((request, response, authentication) -> {
            // Google accounts are keyed by a numeric id; keep their name and email so
            // the admin dashboard can show who they are.
            UserProfileService profiles = userProfiles.getIfAvailable();
            if (profiles != null && authentication.getPrincipal() instanceof OAuth2User user) {
              try {
                profiles.remember(authentication.getName(), user.getAttribute("email"), user.getAttribute("name"));
              } catch (RuntimeException error) {
                log.warn("Could not save profile for {}: {}", authentication.getName(), error.toString());
              }
            }
            toHome.onAuthenticationSuccess(request, response, authentication);
          }));
    }

    return http.build();
  }

  @Bean
  UserDetailsService userDetailsService(
      PasswordEncoder passwordEncoder,
      @Value("${travel-risk.security.employee-username-pattern}") String employeeUsernamePattern,
      @Value("${travel-risk.security.password}") String password,
      @Value("${travel-risk.security.admin-username:}") String adminUsername,
      @Value("${travel-risk.security.admin-password:}") String adminPassword,
      DemoAccounts demoAccounts) {
    Pattern employeePattern = Pattern.compile(employeeUsernamePattern, Pattern.CASE_INSENSITIVE);
    String encodedEmployeePassword = passwordEncoder.encode(password);
    String encodedAdminPassword = adminPassword.isBlank() ? "" : passwordEncoder.encode(adminPassword);
    String encodedDemoAdminPassword = demoAccounts.adminEnabled() ? passwordEncoder.encode(demoAccounts.adminPassword()) : "";
    return username -> {
      if (!adminUsername.isBlank() && !adminPassword.isBlank() && adminUsername.equalsIgnoreCase(username)) {
        return User.withUsername(adminUsername)
            .password(encodedAdminPassword)
            .roles("USER", "ADMIN")
            .build();
      }
      // Read-only: sees the admin dashboard for demo accounts only and cannot change anything.
      if (demoAccounts.isDemoAdmin(username)) {
        return User.withUsername(demoAccounts.adminUsername())
            .password(encodedDemoAdminPassword)
            .roles("USER", "DEMO_ADMIN")
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
