package com.theninjadev.ajoapi.round;

import com.theninjadev.ajoapi.auth.UserSummary;
import java.time.LocalDate;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RoundMapper {

    RoundSummary toSummary(Round round);

    @Mapping(target = "id", source = "participant.id")
    @Mapping(target = "user", source = "user")
    ParticipantSummary toParticipantSummary(RoundParticipant participant, UserSummary user);

    /**
     * The reported status can differ from the cycles.status column, deliberately. The column
     * records whether anyone has contributed yet (contribute reads it to decide when to open the
     * cycle); the API reports whether anyone can, which depends on the date. See
     * Cycle.effectiveStatusAt.
     */
    @Mapping(target = "id", source = "cycle.id")
    @Mapping(target = "beneficiary", source = "beneficiary")
    @Mapping(target = "status", expression = "java(cycle.effectiveStatusAt(today))")
    CycleSummary toCycleSummary(Cycle cycle, UserSummary beneficiary, LocalDate today);
}
