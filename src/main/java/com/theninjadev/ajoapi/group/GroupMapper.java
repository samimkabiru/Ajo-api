package com.theninjadev.ajoapi.group;

import com.theninjadev.ajoapi.auth.UserSummary;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface GroupMapper {

    GroupSummary toSummary(Group group);

    @Mapping(target = "inviterName", source = "inviterName")
    GroupInviteSummary toInviteSummary(GroupInvite invite, String inviterName);

    @Mapping(target = "user", source = "user")
    @Mapping(target = "role", source = "member.role")
    @Mapping(target = "joinedAt", source = "member.joinedAt")
    GroupMemberSummary toMemberSummary(GroupMember member, UserSummary user);
}
