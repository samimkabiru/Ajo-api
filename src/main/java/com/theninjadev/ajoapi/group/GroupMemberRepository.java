package com.theninjadev.ajoapi.group;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupMemberRepository extends JpaRepository<GroupMember, UUID> {

    Optional<GroupMember> findByGroupIdAndUserId(UUID groupId, UUID userId);

    List<GroupMember> findByGroupId(UUID groupId);

    List<GroupMember> findByUserId(UUID userId);

    long countByGroupId(UUID groupId);

    long countByGroupIdAndRole(UUID groupId, GroupRole role);

    boolean existsByGroupIdAndUserId(UUID groupId, UUID userId);
}
