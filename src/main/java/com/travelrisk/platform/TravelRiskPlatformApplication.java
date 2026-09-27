package com.travelrisk.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
@SpringBootApplication
public class TravelRiskPlatformApplication {
  public static void main(String[] args) {
    SpringApplication.run(TravelRiskPlatformApplication.class, args);
  }
}
