package com.travelrisk.platform.service;

import com.travelrisk.platform.database.entities.UserProfile;
import com.travelrisk.platform.repository.UserProfileRepository;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileService {
  private final UserProfileRepository repository;

  public UserProfileService(UserProfileRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public void remember(String username, String email, String displayName) {
    if (username == null || username.isBlank()) return;
    UserProfile profile = repository.findById(username).orElseGet(() -> new UserProfile(username));
    profile.update(blankToNull(email), blankToNull(displayName));
    repository.save(profile);
  }

  @Transactional(readOnly = true)
  public Map<String, UserProfile> findAll(Collection<String> usernames) {
    return repository.findAllById(usernames).stream()
        .collect(Collectors.toMap(UserProfile::getUsername, Function.identity()));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
