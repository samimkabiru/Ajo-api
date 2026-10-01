package com.theninjadev.ajoapi.round;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoundRepository extends JpaRepository<Round, UUID> {

    List<Round> findByGroupId(UUID groupId);

    boolean existsByGroupIdAndStatusIn(UUID groupId, Collection<RoundStatus> statuses);

    @Modifying
    @Query("delete from Round r where r.groupId = :groupId")
    void deleteAllByGroupId(@Param("groupId") UUID groupId);
}
