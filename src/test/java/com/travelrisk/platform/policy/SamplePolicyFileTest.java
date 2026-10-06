package com.travelrisk.platform.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class SamplePolicyFileTest {

  @Test
  void customerSamplePolicyLoadsAndIsStricterThanTheDefault() {
    TravelPolicyService acme = new TravelPolicyService("file:samples/acme-travel-policy.yml", new DefaultResourceLoader());

    assertThat(acme.policy().company()).isEqualTo("Acme Logistics");
    assertThat(acme.evaluate("Medium", 5, "flight", Set.of()).outcome()).isEqualTo("approval_required");
    assertThat(acme.evaluate("Low", 1, "drive", Set.of("official-alert")).outcome()).isEqualTo("blocked");
  }
}
