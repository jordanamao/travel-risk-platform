package com.travelrisk.platform.controllers;

import com.travelrisk.platform.demo.DemoAccounts;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets the public login page show the read-only demo admin login, only while demo data is on. */
@RestController
public class DemoAccountsController {
  private final DemoAccounts demoAccounts;

  public DemoAccountsController(DemoAccounts demoAccounts) {
    this.demoAccounts = demoAccounts;
  }

  @GetMapping("/api/auth/demo-accounts")
  public DemoAccountsResponse demoAccounts() {
    return new DemoAccountsResponse(demoAccounts.adminEnabled()
        ? new Login(demoAccounts.adminUsername(), demoAccounts.adminPassword())
        : null);
  }

  public record DemoAccountsResponse(Login demoAdmin) {}

  public record Login(String username, String password) {}
}
