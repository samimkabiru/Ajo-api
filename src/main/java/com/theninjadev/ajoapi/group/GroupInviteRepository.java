package com.theninjadev.ajoapi.group;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupInviteRepository extends JpaRepository<GroupInvite, UUID> {

    Optional<GroupInvite> findByGroupIdAndPhoneAndStatus(UUID groupId, String phone, InviteStatus status);

    List<GroupInvite> findByPhoneAndStatus(String phone, InviteStatus status);

    /** Every invite the group has sent, whatever its status. Id breaks a same-microsecond tie. */
    List<GroupInvite> findByGroupIdOrderByCreatedAtDescIdDesc(UUID groupId);

    /** Pending invites to this phone, leaving out groups that are archived and can't be joined. */
    @Query("""
            select i from GroupInvite i
            where i.phone = :phone
              and i.status = com.theninjadev.ajoapi.group.InviteStatus.PENDING
              and exists (select 1 from Group g where g.id = i.groupId and g.archivedAt is null)
            """)
    List<GroupInvite> findPendingToJoinableGroups(@Param("phone") String phone);

    @Modifying
    @Query("delete from GroupInvite i where i.groupId = :groupId")
    void deleteAllByGroupId(@Param("groupId") UUID groupId);
}
