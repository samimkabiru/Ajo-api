package com.theninjadev.ajoapi.round;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CycleRepository extends JpaRepository<Cycle, UUID> {

    List<Cycle> findByRoundIdOrderByCycleNumberAsc(UUID roundId);

    Optional<Cycle> findByVacatedByExitId(UUID exitId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cycle c where c.id in :ids order by c.id")
    List<Cycle> findAllByIdForUpdate(@Param("ids") Collection<UUID> ids);
}
