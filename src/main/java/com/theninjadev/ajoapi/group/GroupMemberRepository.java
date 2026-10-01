package com.theninjadev.ajoapi.group;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupMemberRepository extends JpaRepository<GroupMember, UUID> {

    Optional<GroupMember> findByGroupIdAndUserId(UUID groupId, UUID userId);

    List<GroupMember> findByGroupId(UUID groupId);

    List<GroupMember> findByUserId(UUID userId);

    long countByGroupId(UUID groupId);

    long countByGroupIdAndRole(UUID groupId, GroupRole role);

    boolean existsByGroupIdAndUserId(UUID groupId, UUID userId);

    @Modifying
    @Query("delete from GroupMember m where m.groupId = :groupId")
    void deleteAllByGroupId(@Param("groupId") UUID groupId);
}
