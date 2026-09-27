package com.travelrisk.platform.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

@Configuration
public class HttpClientConfig {
  @Bean
  RestClientCustomizer restClientTimeouts(
      @Value("${travel-risk.http.connect-timeout-ms:1500}") long connectTimeoutMs,
      @Value("${travel-risk.http.read-timeout-ms:3000}") long readTimeoutMs) {
    return builder -> {
      HttpClient httpClient = HttpClient.newBuilder()
          .connectTimeout(Duration.ofMillis(connectTimeoutMs))
          .build();
      JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
      requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
      builder.requestFactory(requestFactory);
    };
  }
}
