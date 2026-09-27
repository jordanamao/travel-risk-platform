package com.travelrisk.platform;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthenticationManager authenticationManager;
  private final JwtEncoder jwtEncoder;
  private final String issuer;

  public AuthController(
      AuthenticationManager authenticationManager,
      JwtEncoder jwtEncoder,
      @Value("${travel-risk.jwt.issuer}") String issuer) {
    this.authenticationManager = authenticationManager;
    this.jwtEncoder = jwtEncoder;
    this.issuer = issuer;
  }

  @PostMapping("/token")
  public TokenResponse token(@RequestBody LoginRequest request) {
    Authentication authentication = authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(request.username(), request.password()));
    Instant now = Instant.now();
    Instant expiresAt = now.plus(8, ChronoUnit.HOURS);
    String scope = authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .collect(Collectors.joining(" "));
    JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer(issuer)
        .issuedAt(now)
        .expiresAt(expiresAt)
        .subject(authentication.getName())
        .claim("scope", scope)
        .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new TokenResponse(token, "Bearer", expiresAt);
  }

  public record LoginRequest(String username, String password) {}

  public record TokenResponse(String accessToken, String tokenType, Instant expiresAt) {}
}
