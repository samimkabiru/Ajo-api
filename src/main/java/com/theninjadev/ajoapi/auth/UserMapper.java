package com.theninjadev.ajoapi.auth;

import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserMapper {
    UserSummary toSummary(User user);
}
