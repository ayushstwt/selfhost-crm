package com.ayshriv.selfhostcrm.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.TeamRole;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.exception.ForbiddenException;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeamAccessPolicyTest {

	private final TeamAccessPolicy policy = new TeamAccessPolicy();

	private static UserPrincipal principal(long id, Role role, TeamMembership... memberships) {
		User user = new User(id);
		user.setEmail("u" + id + "@example.com");
		user.setPasswordHash("x");
		user.setRole(role);
		return new UserPrincipal(user, List.of(memberships));
	}

	@Test
	void adminSeesAndManagesEveryTeamAndEveryTarget() {
		UserPrincipal admin = principal(1, Role.ADMIN);
		assertThat(policy.canView(admin, 5L)).isTrue();
		assertThat(policy.canManageMembers(admin, 5L)).isTrue();
		assertThat(policy.canChangeMember(admin, 2L, Role.ADMIN)).isTrue();
		assertThat(policy.canChangeMember(admin, 2L, Role.MANAGER)).isTrue();
	}

	@Test
	void managerManagesOnlyTeamsTheyLeadAndOnlyMemberAccounts() {
		UserPrincipal lead = principal(2, Role.MANAGER, new TeamMembership(5L, TeamRole.LEAD),
				new TeamMembership(6L, TeamRole.MEMBER));
		assertThat(policy.canView(lead, 5L)).isTrue();
		assertThat(policy.canView(lead, 6L)).isTrue();
		assertThat(policy.canView(lead, 7L)).isFalse();
		assertThat(policy.canManageMembers(lead, 5L)).isTrue();
		assertThat(policy.canManageMembers(lead, 6L)).as("plain member of that team").isFalse();
		assertThat(policy.canManageMembers(lead, 7L)).isFalse();
		assertThat(policy.canChangeMember(lead, 9L, Role.MEMBER)).isTrue();
		assertThat(policy.canChangeMember(lead, 9L, Role.MANAGER)).isFalse();
		assertThat(policy.canChangeMember(lead, 9L, Role.ADMIN)).isFalse();
		assertThat(policy.canChangeMember(lead, 2L, Role.MEMBER)).as("themselves").isFalse();
	}

	@Test
	void memberOnlySeesOwnTeamsAndManagesNothing() {
		UserPrincipal member = principal(3, Role.MEMBER, new TeamMembership(5L, TeamRole.LEAD));
		assertThat(policy.canView(member, 5L)).isTrue();
		assertThat(policy.canView(member, 6L)).isFalse();
		assertThat(policy.canManageMembers(member, 5L)).as("a LEAD team role is not enough for a MEMBER account")
				.isFalse();
		assertThat(policy.canChangeMember(member, 9L, Role.MEMBER)).isFalse();
	}

	@Test
	void assertMethodsThrowForbiddenAndReturnWhenAllowed() {
		UserPrincipal member = principal(3, Role.MEMBER);
		assertThatThrownBy(() -> policy.assertCanView(member, 5L)).isInstanceOf(ForbiddenException.class);
		assertThatThrownBy(() -> policy.assertCanManageMembers(member, 5L)).isInstanceOf(ForbiddenException.class);
		assertThatThrownBy(() -> policy.assertCanViewTeamsOfUser(member, 4L)).isInstanceOf(ForbiddenException.class);
		assertThatCode(() -> policy.assertCanViewTeamsOfUser(member, 3L)).doesNotThrowAnyException();
		assertThatCode(() -> policy.assertCanViewTeamsOfUser(principal(1, Role.ADMIN), 4L)).doesNotThrowAnyException();
	}

}
