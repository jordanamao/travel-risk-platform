package com.travelrisk.platform.web;

/** Thrown when a requested record does not exist for the current user; mapped to 404. */
public class ResourceNotFoundException extends RuntimeException {
  public ResourceNotFoundException(String message) {
    super(message);
  }
}
