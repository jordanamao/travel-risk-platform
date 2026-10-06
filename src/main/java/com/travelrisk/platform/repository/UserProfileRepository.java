package com.travelrisk.platform.repository;

import com.travelrisk.platform.database.entities.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileRepository extends JpaRepository<UserProfile, String> {}
