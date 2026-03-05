package com.tripchat.repository;

import com.tripchat.model.Group;
import com.tripchat.model.GroupMember;
import com.tripchat.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * GroupMemberRepository — data access for GroupMember junction table.
 *
 * findByUser: loads all groups a user belongs to.
 *   Used in GET /api/v1/groups — "my groups" listing.
 *   @Query with JOIN FETCH prevents N+1 — loads group data in one query
 *   instead of issuing a separate SELECT per membership record.
 *
 * countByGroup: member count per group.
 *   Used for max-member enforcement and GroupResponse.memberCount.
 *   Note: called once per group in listing — potential N+1 if not careful.
 *   Acceptable at Phase 1 scale; Redis cache will mitigate later.
 *
 * existsByGroupAndUser: duplicate join check.
 *   Pessimistic — check before insert (same reasoning as auth duplicate check).
 */
@Repository
public interface GroupMemberRepository extends JpaRepository<GroupMember, UUID> {

    // JOIN FETCH group to avoid N+1 when listing user's groups
    @Query("SELECT gm FROM GroupMember gm JOIN FETCH gm.group g WHERE gm.user = :user AND g.isActive = true")
    List<GroupMember> findByUser(@Param("user") User user);

    // JOIN FETCH user to avoid N+1 when loading all members of a group
    // Used by PresenceService to check online status for each member
    @Query("SELECT gm FROM GroupMember gm JOIN FETCH gm.user WHERE gm.group = :group")
    List<GroupMember> findByGroup(@Param("group") Group group);

    Optional<GroupMember> findByGroupAndUser(Group group, User user);

    boolean existsByGroupAndUser(Group group, User user);

    int countByGroup(Group group);
}
