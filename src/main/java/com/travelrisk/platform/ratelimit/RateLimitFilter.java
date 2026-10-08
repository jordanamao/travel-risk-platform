package com.travelrisk.platform.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelrisk.platform.web.ApiErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limits {@code GET /api/analyze}, its alternatives and its brief per authenticated user (client IP when unauthenticated) and
 * {@code POST /api/auth/token} per client IP. Runs in the security chain right after the JWT filter
 * so the authenticated principal is known; a rejected request never reaches the controller.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {
  static final String ANALYZE_PATH = "/api/analyze";
  static final String ALTERNATIVES_PATH = "/api/analyze/alternatives";
  static final String BRIEF_PATH = "/api/analyze/brief";
  static final String TOKEN_PATH = "/api/auth/token";

  private final boolean enabled;
  private final ApiErrorWriter errorWriter;
  private final FixedWindowRateLimiter analyzeLimiter;
  private final FixedWindowRateLimiter tokenLimiter;

  public RateLimitFilter(
      ObjectMapper objectMapper,
      @Value("${travel-risk.rate-limit.enabled:true}") boolean enabled,
      @Value("${travel-risk.rate-limit.analyze.requests:30}") int analyzeRequests,
      @Value("${travel-risk.rate-limit.analyze.window:60s}") Duration analyzeWindow,
      @Value("${travel-risk.rate-limit.token.requests:10}") int tokenRequests,
      @Value("${travel-risk.rate-limit.token.window:60s}") Duration tokenWindow) {
    this.enabled = enabled;
    this.errorWriter = new ApiErrorWriter(objectMapper);
    Clock clock = Clock.systemUTC();
    this.analyzeLimiter = new FixedWindowRateLimiter(analyzeRequests, analyzeWindow, clock);
    this.tokenLimiter = new FixedWindowRateLimiter(tokenRequests, tokenWindow, clock);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !enabled || limiterFor(request) == null;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    FixedWindowRateLimiter limiter = limiterFor(request);
    String key = limiter == analyzeLimiter ? analyzeKey(request) : "ip:" + request.getRemoteAddr();
    FixedWindowRateLimiter.Decision decision = limiter.tryAcquire(key);
    if (decision.allowed()) {
      filterChain.doFilter(request, response);
      return;
    }
    long retryAfterSeconds = Math.max(1, (decision.retryAfter().toMillis() + 999) / 1000);
    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
    errorWriter.write(response, HttpStatus.TOO_MANY_REQUESTS,
        "Too many requests. Retry after " + retryAfterSeconds + " seconds.");
  }

  private FixedWindowRateLimiter limiterFor(HttpServletRequest request) {
    String method = request.getMethod();
    String path = request.getRequestURI();
    if ((ANALYZE_PATH.equals(path) || ALTERNATIVES_PATH.equals(path) || BRIEF_PATH.equals(path)) && ("GET".equals(method) || "HEAD".equals(method))) {
      return analyzeLimiter;
    }
    if (TOKEN_PATH.equals(path) && "POST".equals(method)) {
      return tokenLimiter;
    }
    return null;
  }

  private String analyzeKey(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken)
        && authentication.getName() != null) {
      return "user:" + authentication.getName();
    }
    return "ip:" + request.getRemoteAddr();
  }
}
