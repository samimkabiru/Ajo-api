package com.theninjadev.ajoapi.group;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupInviteRepository extends JpaRepository<GroupInvite, UUID> {

    Optional<GroupInvite> findByGroupIdAndPhoneAndStatus(UUID groupId, String phone, InviteStatus status);

    List<GroupInvite> findByPhoneAndStatus(String phone, InviteStatus status);
}
