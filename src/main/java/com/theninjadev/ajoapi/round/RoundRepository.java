package com.theninjadev.ajoapi.round;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoundRepository extends JpaRepository<Round, UUID> {

    List<Round> findByGroupId(UUID groupId);

    boolean existsByGroupIdAndStatusIn(UUID groupId, Collection<RoundStatus> statuses);
}
