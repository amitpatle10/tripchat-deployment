package com.tripchat.service;

import com.tripchat.dto.response.PresenceResponse;
import com.tripchat.exception.GroupNotFoundException;
import com.tripchat.exception.NotMemberException;
import com.tripchat.model.Group;
import com.tripchat.model.GroupMember;
import com.tripchat.model.User;
import com.tripchat.model.enums.AuthProvider;
import com.tripchat.model.enums.MemberRole;
import com.tripchat.repository.GroupMemberRepository;
import com.tripchat.repository.GroupRepository;
import com.tripchat.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PresenceService")
class PresenceServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private GroupRepository groupRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private PresenceService presenceService;

    private User alice;
    private User bob;
    private Group group;
    private GroupMember aliceMembership;
    private GroupMember bobMembership;

    @BeforeEach
    void setUp() {
        alice = User.builder().id(UUID.randomUUID()).email("alice@test.com")
                .username("alice").displayName("Alice")
                .authProvider(AuthProvider.LOCAL).isActive(true).build();

        bob = User.builder().id(UUID.randomUUID()).email("bob@test.com")
                .username("bob").displayName("Bob")
                .authProvider(AuthProvider.LOCAL).isActive(true).build();

        group = Group.builder().id(UUID.randomUUID()).name("Trip to Goa")
                .createdBy(alice).inviteCode("TRIP1234").isActive(true).build();

        aliceMembership = GroupMember.builder().id(UUID.randomUUID())
                .group(group).user(alice).role(MemberRole.ADMIN).build();
        bobMembership = GroupMember.builder().id(UUID.randomUUID())
                .group(group).user(bob).role(MemberRole.MEMBER).build();
    }

    // ── markOnline / markOffline / refreshPresence ─────────────────────────

    @Nested
    @DisplayName("markOnline()")
    class MarkOnline {

        @Test
        @DisplayName("should set presence key with 30s TTL")
        void shouldSetPresenceKeyWithTtl() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            presenceService.markOnline(alice.getId());

            verify(valueOps).set(
                eq("presence:" + alice.getId()),
                eq("1"),
                eq(30L),
                eq(TimeUnit.SECONDS)
            );
        }
    }

    @Nested
    @DisplayName("markOffline()")
    class MarkOffline {

        @Test
        @DisplayName("should delete presence key immediately")
        void shouldDeletePresenceKey() {
            presenceService.markOffline(alice.getId());

            verify(redisTemplate).delete("presence:" + alice.getId());
        }
    }

    @Nested
    @DisplayName("refreshPresence()")
    class RefreshPresence {

        @Test
        @DisplayName("should extend TTL to 30s without overwriting value")
        void shouldExtendTtl() {
            presenceService.refreshPresence(alice.getId());

            verify(redisTemplate).expire(
                eq("presence:" + alice.getId()),
                eq(30L),
                eq(TimeUnit.SECONDS)
            );
        }
    }

    // ── getOnlineMembers() ────────────────────────────────────────────────

    @Nested
    @DisplayName("getOnlineMembers()")
    class GetOnlineMembers {

        @Test
        @DisplayName("should return only online members")
        void shouldReturnOnlineMembers() {
            arrangeMember();
            when(groupMemberRepository.findByGroup(group))
                .thenReturn(List.of(aliceMembership, bobMembership));
            // Alice is online, Bob is not
            when(redisTemplate.hasKey("presence:" + alice.getId())).thenReturn(true);
            when(redisTemplate.hasKey("presence:" + bob.getId())).thenReturn(false);

            List<PresenceResponse> result = presenceService.getOnlineMembers("alice@test.com", group.getId());

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUsername()).isEqualTo("alice");
        }

        @Test
        @DisplayName("should return empty list when no members are online")
        void shouldReturnEmptyWhenNoneOnline() {
            arrangeMember();
            when(groupMemberRepository.findByGroup(group))
                .thenReturn(List.of(aliceMembership, bobMembership));
            when(redisTemplate.hasKey(anyString())).thenReturn(false);

            List<PresenceResponse> result = presenceService.getOnlineMembers("alice@test.com", group.getId());

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return all members when all are online")
        void shouldReturnAllWhenAllOnline() {
            arrangeMember();
            when(groupMemberRepository.findByGroup(group))
                .thenReturn(List.of(aliceMembership, bobMembership));
            when(redisTemplate.hasKey(anyString())).thenReturn(true);

            List<PresenceResponse> result = presenceService.getOnlineMembers("alice@test.com", group.getId());

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("should throw GroupNotFoundException for unknown group")
        void shouldThrowForUnknownGroup() {
            when(userRepository.findByEmailIgnoreCase("alice@test.com")).thenReturn(Optional.of(alice));
            when(groupRepository.findById(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> presenceService.getOnlineMembers("alice@test.com", UUID.randomUUID()))
                .isInstanceOf(GroupNotFoundException.class);
        }

        @Test
        @DisplayName("should throw GroupNotFoundException for inactive group")
        void shouldThrowForInactiveGroup() {
            Group inactive = Group.builder().id(UUID.randomUUID()).name("Old")
                    .createdBy(alice).inviteCode("OLD12345").isActive(false).build();
            when(userRepository.findByEmailIgnoreCase("alice@test.com")).thenReturn(Optional.of(alice));
            when(groupRepository.findById(inactive.getId())).thenReturn(Optional.of(inactive));

            assertThatThrownBy(() -> presenceService.getOnlineMembers("alice@test.com", inactive.getId()))
                .isInstanceOf(GroupNotFoundException.class);
        }

        @Test
        @DisplayName("should throw NotMemberException for non-member")
        void shouldThrowForNonMember() {
            when(userRepository.findByEmailIgnoreCase("alice@test.com")).thenReturn(Optional.of(alice));
            when(groupRepository.findById(group.getId())).thenReturn(Optional.of(group));
            when(groupMemberRepository.existsByGroupAndUser(group, alice)).thenReturn(false);

            assertThatThrownBy(() -> presenceService.getOnlineMembers("alice@test.com", group.getId()))
                .isInstanceOf(NotMemberException.class);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void arrangeMember() {
        when(userRepository.findByEmailIgnoreCase("alice@test.com")).thenReturn(Optional.of(alice));
        when(groupRepository.findById(group.getId())).thenReturn(Optional.of(group));
        when(groupMemberRepository.existsByGroupAndUser(group, alice)).thenReturn(true);
    }
}
