package com.theninjadev.ajoapi.round;

import com.theninjadev.ajoapi.auth.UserSummary;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RoundMapper {

    RoundSummary toSummary(Round round);

    @Mapping(target = "id", source = "participant.id")
    @Mapping(target = "user", source = "user")
    ParticipantSummary toParticipantSummary(RoundParticipant participant, UserSummary user);

    @Mapping(target = "id", source = "cycle.id")
    @Mapping(target = "beneficiary", source = "beneficiary")
    CycleSummary toCycleSummary(Cycle cycle, UserSummary beneficiary);
}
