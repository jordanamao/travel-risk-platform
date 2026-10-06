package com.travelrisk.platform.web;

/** The one JSON error shape every API endpoint returns: {@code {"error": "message"}}. */
public record ApiError(String error) {}
