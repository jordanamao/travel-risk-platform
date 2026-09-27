package com.travelrisk.platform;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ObjectProvider<ClientRegistrationRepository> clientRegistrations,
      JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
    http
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/login", "/login.html", "/styles.css", "/api/auth/**", "/oauth2/**", "/login/oauth2/**").permitAll()
            .anyRequest().authenticated())
        .formLogin(login -> login
            .loginPage("/login")
            .defaultSuccessUrl("/", true)
            .permitAll())
        .logout(logout -> logout
            .logoutSuccessUrl("/login?logout")
            .permitAll())
        .exceptionHandling(exception -> exception
            .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login")))
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

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
      @Value("${travel-risk.security.username}") String username,
      @Value("${travel-risk.security.password}") String password) {
    UserDetails user = User.withUsername(username)
        .password(passwordEncoder.encode(password))
        .roles("USER")
        .build();
    return new InMemoryUserDetailsManager(user);
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
