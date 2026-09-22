package com.theninjadev.ajoapi.swap;

import com.theninjadev.ajoapi.auth.UserSummary;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface SwapMapper {

    @Mapping(target = "id", source = "request.id")
    @Mapping(target = "requester", source = "requester")
    @Mapping(target = "target", source = "target")
    SwapRequestSummary toSummary(PositionSwapRequest request, UserSummary requester, UserSummary target);
}
