package com.travelrisk.platform.repository;

import com.travelrisk.platform.database.entities.AlertOutboxEntry;
import com.travelrisk.platform.database.entities.AlertOutboxEntry.Status;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AlertOutboxRepository extends JpaRepository<AlertOutboxEntry, Long> {
  /** Rows ready to send: PENDING and due, or SENDING with an expired lease (the worker holding it died). */
  @Query("""
      select e.id from AlertOutboxEntry e
      where e.status in (com.travelrisk.platform.database.entities.AlertOutboxEntry.Status.PENDING,
                         com.travelrisk.platform.database.entities.AlertOutboxEntry.Status.SENDING)
        and e.nextAttemptAt <= :now
      order by e.id""")
  List<Long> findDueIds(@Param("now") Instant now, Pageable page);

  /**
   * Takes one row for this worker with a single conditional update. Two app instances can race for
   * the same row, but only one update matches, so only one of them sends it. Returns 1 when claimed.
   */
  @Modifying(clearAutomatically = true)
  @Transactional
  @Query("""
      update AlertOutboxEntry e
      set e.status = com.travelrisk.platform.database.entities.AlertOutboxEntry.Status.SENDING,
          e.nextAttemptAt = :leaseUntil
      where e.id = :id
        and e.status in (com.travelrisk.platform.database.entities.AlertOutboxEntry.Status.PENDING,
                         com.travelrisk.platform.database.entities.AlertOutboxEntry.Status.SENDING)
        and e.nextAttemptAt <= :now""")
  int claim(@Param("id") Long id, @Param("now") Instant now, @Param("leaseUntil") Instant leaseUntil);

  boolean existsByDedupeKey(String dedupeKey);

  long countByStatus(Status status);

  List<AlertOutboxEntry> findByNotificationIdOrderByIdAsc(Long notificationId);
}
