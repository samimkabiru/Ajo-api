package com.theninjadev.ajoapi.round;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CycleRepository extends JpaRepository<Cycle, UUID> {

    List<Cycle> findByRoundIdOrderByCycleNumberAsc(UUID roundId);
}
