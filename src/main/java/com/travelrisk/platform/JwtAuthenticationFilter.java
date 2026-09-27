package com.travelrisk.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
  private final JwtDecoder jwtDecoder;

  public JwtAuthenticationFilter(JwtDecoder jwtDecoder) {
    this.jwtDecoder = jwtDecoder;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      filterChain.doFilter(request, response);
      return;
    }

    try {
      Jwt jwt = jwtDecoder.decode(authHeader.substring(7));
      String scope = jwt.getClaimAsString("scope");
      List<SimpleGrantedAuthority> authorities = Arrays.stream((scope == null ? "" : scope).split(" "))
          .filter(authority -> !authority.isBlank())
          .map(SimpleGrantedAuthority::new)
          .toList();
      UsernamePasswordAuthenticationToken authentication =
          new UsernamePasswordAuthenticationToken(jwt.getSubject(), null, authorities);
      authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
      SecurityContextHolder.getContext().setAuthentication(authentication);
    } catch (JwtException error) {
      SecurityContextHolder.clearContext();
    }

    filterChain.doFilter(request, response);
  }
}
