package com.travelrisk.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

/**
 * Registers Google as an OAuth2 login provider only when a client ID is configured, so the app
 * (and its tests) still start with form login alone when GOOGLE_CLIENT_ID is unset.
 */
@Configuration
@ConditionalOnExpression("!'${travel-risk.oauth.google.client-id:}'.isBlank()")
public class GoogleOAuthConfig {
  @Bean
  ClientRegistrationRepository clientRegistrationRepository(
      @Value("${travel-risk.oauth.google.client-id}") String clientId,
      @Value("${travel-risk.oauth.google.client-secret:}") String clientSecret) {
    return new InMemoryClientRegistrationRepository(
        CommonOAuth2Provider.GOOGLE.getBuilder("google")
            .clientId(clientId)
            .clientSecret(clientSecret)
            .build());
  }
}
