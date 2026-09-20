package com.theninjadev.ajoapi.contribution;

import com.theninjadev.ajoapi.auth.UserSummary;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ContributionMapper {

    @Mapping(target = "id", source = "contribution.id")
    @Mapping(target = "participantId", source = "contribution.participantId")
    @Mapping(target = "participant", source = "participant")
    ContributionSummary toSummary(Contribution contribution, UserSummary participant);
}
