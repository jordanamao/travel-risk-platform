package com.travelrisk.platform;

import com.travelrisk.platform.policy.TravelPolicyService;
import org.springframework.core.io.DefaultResourceLoader;

public final class TestPolicies {
  private TestPolicies() {}

  /** The policy file the app ships with. */
  public static TravelPolicyService defaultPolicy() {
    return new TravelPolicyService("classpath:travel-policy.yml", new DefaultResourceLoader());
  }
}
