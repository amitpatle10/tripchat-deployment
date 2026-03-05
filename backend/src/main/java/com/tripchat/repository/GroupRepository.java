package com.tripchat.repository;

import com.tripchat.model.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * GroupRepository — data access for Group entity.
 *
 * findByInviteCode: the primary join lookup.
 *   User provides code → we find the group → verify membership conditions.
 *   Returns Optional — caller handles "code not found" as InvalidInviteCodeException.
 *
 * existsByInviteCode: used during code generation to prevent collisions.
 *   Extremely rare at our scale (32^8 = 1 trillion combinations, ~1000 groups).
 *   Still checked — correctness over probability.
 */
@Repository
public interface GroupRepository extends JpaRepository<Group, UUID> {

    Optional<Group> findByInviteCode(String inviteCode);

    boolean existsByInviteCode(String inviteCode);
}
