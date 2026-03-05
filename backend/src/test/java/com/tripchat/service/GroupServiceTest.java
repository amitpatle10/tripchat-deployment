package com.tripchat.service;

import com.tripchat.dto.request.CreateGroupRequest;
import com.tripchat.dto.request.JoinGroupRequest;
import com.tripchat.dto.response.GroupResponse;
import com.tripchat.exception.*;
import com.tripchat.model.Group;
import com.tripchat.model.GroupMember;
import com.tripchat.model.User;
import com.tripchat.model.enums.AuthProvider;
import com.tripchat.model.enums.MemberRole;
import com.tripchat.repository.GroupMemberRepository;
import com.tripchat.repository.GroupRepository;
import com.tripchat.repository.UserRepository;
import com.tripchat.util.InviteCodeGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GroupService")
class GroupServiceTest {

    @Mock private GroupRepository groupRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private InviteCodeGenerator inviteCodeGenerator;
    @Mock private UnreadService unreadService;

    @InjectMocks
    private GroupService groupService;

    private User testUser;
    private Group testGroup;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(UUID.randomUUID())
                .email("amit@test.com")
                .username("amit_patle")
                .displayName("Amit")
                .authProvider(AuthProvider.LOCAL)
                .isActive(true)
                .build();

        testGroup = Group.builder()
                .id(UUID.randomUUID())
                .name("Trip to Goa")
                .description("Planning our Goa trip")
                .createdBy(testUser)
                .inviteCode("TRIP1234")
                .isActive(true)
                .build();
    }

    // ── Create Group ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createGroup()")
    class CreateGroup {

        @Test
        @DisplayName("should create group and return response with ADMIN role")
        void shouldCreateGroupSuccessfully() {
            when(userRepository.findByEmailIgnoreCase("amit@test.com")).thenReturn(Optional.of(testUser));
            when(inviteCodeGenerator.generate()).thenReturn("TRIP1234");
            when(groupRepository.existsByInviteCode("TRIP1234")).thenReturn(false);
            when(groupRepository.save(any(Group.class))).thenReturn(testGroup);
            when(groupMemberRepository.save(any(GroupMember.class))).thenAnswer(inv -> inv.getArgument(0));

            GroupResponse response = groupService.createGroup("amit@test.com",
                    new CreateGroupRequest("Trip to Goa", "Planning our Goa trip"));

            assertThat(response.getName()).isEqualTo("Trip to Goa");
            assertThat(response.getMyRole()).isEqualTo(MemberRole.ADMIN);
            assertThat(response.getMemberCount()).isEqualTo(1);
            assertThat(response.getInviteCode()).isEqualTo("TRIP1234");
        }

        @Test
        @DisplayName("should save creator as ADMIN in group_members")
        void shouldSaveCreatorAsAdmin() {
            when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(testUser));
            when(inviteCodeGenerator.generate()).thenReturn("TRIP1234");
            when(groupRepository.existsByInviteCode(any())).thenReturn(false);
            when(groupRepository.save(any())).thenReturn(testGroup);
            when(groupMemberRepository.save(any(GroupMember.class))).thenAnswer(inv -> inv.getArgument(0));

            groupService.createGroup("amit@test.com", new CreateGroupRequest("Trip to Goa", null));

            verify(groupMemberRepository).save(argThat(m -> m.getRole() == MemberRole.ADMIN));
        }

        @Test
        @DisplayName("should retry code generation on collision")
        void shouldRetryOnCodeCollision() {
            when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(testUser));
            when(inviteCodeGenerator.generate()).thenReturn("AAAAAAAA", "TRIP1234");
            when(groupRepository.existsByInviteCode("AAAAAAAA")).thenReturn(true);
            when(groupRepository.existsByInviteCode("TRIP1234")).thenReturn(false);
            when(groupRepository.save(any())).thenReturn(testGroup);
            when(groupMemberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            groupService.createGroup("amit@test.com", new CreateGroupRequest("Trip to Goa", null));

            verify(inviteCodeGenerator, times(2)).generate();
        }
    }

    // ── Join Group ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("joinGroup()")
    class JoinGroup {

        @Test
        @DisplayName("should join group successfully with valid invite code")
        void shouldJoinSuccessfully() {
            User newUser = User.builder().id(UUID.randomUUID()).email("new@test.com")
                    .username("newuser").isActive(true).authProvider(AuthProvider.LOCAL).build();

            when(userRepository.findByEmailIgnoreCase("new@test.com")).thenReturn(Optional.of(newUser));
            when(groupRepository.findByInviteCode("TRIP1234")).thenReturn(Optional.of(testGroup));
            when(groupMemberRepository.existsByGroupAndUser(testGroup, newUser)).thenReturn(false);
            when(groupMemberRepository.countByGroup(testGroup)).thenReturn(5);
            when(groupMemberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            GroupResponse response = groupService.joinGroup("new@test.com", new JoinGroupRequest("TRIP1234"));

            assertThat(response.getMyRole()).isEqualTo(MemberRole.MEMBER);
            assertThat(response.getMemberCount()).isEqualTo(6);
        }

        @Test
        @DisplayName("should throw InvalidInviteCodeException when code is wrong")
        void shouldThrowWhenInviteCodeInvalid() {
            when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(testUser));
            when(groupRepository.findByInviteCode("BADCODE1")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> groupService.joinGroup("amit@test.com", new JoinGroupRequest("BADCODE1")))
                    .isInstanceOf(InvalidInviteCodeException.class)
                    .hasMessage("Invalid invite code");
        }

        @Test
        @DisplayName("should throw AlreadyMemberException when user is already in group")
        void shouldThrowWhenAlreadyMember() {
            when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(testUser));
            when(groupRepository.findByInviteCode("TRIP1234")).thenReturn(Optional.of(testGroup));
            when(groupMemberRepository.existsByGroupAndUser(testGroup, testUser)).thenReturn(true);

            assertThatThrownBy(() -> groupService.joinGroup("amit@test.com", new JoinGroupRequest("TRIP1234")))
                    .isInstanceOf(AlreadyMemberException.class);
        }

        @Test
        @DisplayName("should throw GroupFullException when group has 1000 members")
        void shouldThrowWhenGroupFull() {
            when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(testUser));
            when(groupRepository.findByInviteCode("TRIP1234")).thenReturn(Optional.of(testGroup));
            when(groupMemberRepository.existsByGroupAndUser(any(), any())).thenReturn(false);
            when(groupMemberRepository.countByGroup(testGroup)).thenReturn(1000);

            assertThatThrownBy(() -> groupService.joinGroup("amit@test.com", new JoinGroupRequest("TRIP1234")))
                    .isInstanceOf(GroupFullException.class);
        }
    }

    // ── Leave Group ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("leaveGroup()")
    class LeaveGroup {

        @Test
        @DisplayName("should leave group successfully as MEMBER")
        void shouldLeaveSuccessfully() {
            GroupMember membership = GroupMember.builder()
                    .group(testGroup).user(testUser).role(MemberRole.MEMBER).build();

            when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(testUser));
            when(groupRepository.findById(testGroup.getId())).thenReturn(Optional.of(testGroup));
            when(groupMemberRepository.findByGroupAndUser(testGroup, testUser)).thenReturn(Optional.of(membership));

            groupService.leaveGroup("amit@test.com", testGroup.getId());

            verify(groupMemberRepository).delete(membership);
        }

        @Test
        @DisplayName("should throw AdminCannotLeaveException when admin tries to leave")
        void shouldThrowWhenAdminLeaves() {
            GroupMember adminMembership = GroupMember.builder()
                    .group(testGroup).user(testUser).role(MemberRole.ADMIN).build();

            when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(testUser));
            when(groupRepository.findById(testGroup.getId())).thenReturn(Optional.of(testGroup));
            when(groupMemberRepository.findByGroupAndUser(testGroup, testUser)).thenReturn(Optional.of(adminMembership));

            assertThatThrownBy(() -> groupService.leaveGroup("amit@test.com", testGroup.getId()))
                    .isInstanceOf(AdminCannotLeaveException.class);

            verify(groupMemberRepository, never()).delete(any());
        }

        @Test
        @DisplayName("should throw NotMemberException when non-member tries to leave")
        void shouldThrowWhenNotMember() {
            when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(testUser));
            when(groupRepository.findById(testGroup.getId())).thenReturn(Optional.of(testGroup));
            when(groupMemberRepository.findByGroupAndUser(testGroup, testUser)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> groupService.leaveGroup("amit@test.com", testGroup.getId()))
                    .isInstanceOf(NotMemberException.class);
        }

        @Test
        @DisplayName("should throw GroupNotFoundException when group does not exist")
        void shouldThrowWhenGroupNotFound() {
            UUID fakeId = UUID.randomUUID();
            when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(testUser));
            when(groupRepository.findById(fakeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> groupService.leaveGroup("amit@test.com", fakeId))
                    .isInstanceOf(GroupNotFoundException.class);
        }
    }

    // ── Regenerate Invite Code ────────────────────────────────────────────────

    @Nested
    @DisplayName("regenerateInviteCode()")
    class RegenerateInviteCode {

        @Test
        @DisplayName("should regenerate code when user is ADMIN")
        void shouldRegenerateWhenAdmin() {
            GroupMember adminMembership = GroupMember.builder()
                    .group(testGroup).user(testUser).role(MemberRole.ADMIN).build();

            when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(testUser));
            when(groupRepository.findById(testGroup.getId())).thenReturn(Optional.of(testGroup));
            when(groupMemberRepository.findByGroupAndUser(testGroup, testUser)).thenReturn(Optional.of(adminMembership));
            when(inviteCodeGenerator.generate()).thenReturn("NEWCODE1");
            when(groupRepository.existsByInviteCode("NEWCODE1")).thenReturn(false);
            when(groupRepository.save(any())).thenReturn(testGroup);
            when(groupMemberRepository.countByGroup(any())).thenReturn(1);
            when(unreadService.getCount(any(), any())).thenReturn(0);

            groupService.regenerateInviteCode("amit@test.com", testGroup.getId());

            verify(groupRepository).save(argThat(g -> "NEWCODE1".equals(g.getInviteCode())));
        }

        @Test
        @DisplayName("should throw UnauthorizedGroupActionException when MEMBER tries to regenerate")
        void shouldThrowWhenMemberTriesToRegenerate() {
            GroupMember memberMembership = GroupMember.builder()
                    .group(testGroup).user(testUser).role(MemberRole.MEMBER).build();

            when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(testUser));
            when(groupRepository.findById(testGroup.getId())).thenReturn(Optional.of(testGroup));
            when(groupMemberRepository.findByGroupAndUser(testGroup, testUser)).thenReturn(Optional.of(memberMembership));

            assertThatThrownBy(() -> groupService.regenerateInviteCode("amit@test.com", testGroup.getId()))
                    .isInstanceOf(UnauthorizedGroupActionException.class);
        }
    }

    // ── Get My Groups ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getMyGroups()")
    class GetMyGroups {

        @Test
        @DisplayName("should return only groups user is a member of")
        void shouldReturnMyGroups() {
            GroupMember membership = GroupMember.builder()
                    .group(testGroup).user(testUser).role(MemberRole.ADMIN).build();

            when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(testUser));
            when(groupMemberRepository.findByUser(testUser)).thenReturn(List.of(membership));
            when(groupMemberRepository.countByGroup(testGroup)).thenReturn(1);
            when(unreadService.getAllCounts(testUser.getId())).thenReturn(java.util.Map.of());

            List<GroupResponse> groups = groupService.getMyGroups("amit@test.com");

            assertThat(groups).hasSize(1);
            assertThat(groups.get(0).getName()).isEqualTo("Trip to Goa");
        }

        @Test
        @DisplayName("should return empty list when user has no groups")
        void shouldReturnEmptyListWhenNoGroups() {
            when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(testUser));
            when(groupMemberRepository.findByUser(testUser)).thenReturn(List.of());
            when(unreadService.getAllCounts(testUser.getId())).thenReturn(java.util.Map.of());

            List<GroupResponse> groups = groupService.getMyGroups("amit@test.com");

            assertThat(groups).isEmpty();
        }
    }
}
