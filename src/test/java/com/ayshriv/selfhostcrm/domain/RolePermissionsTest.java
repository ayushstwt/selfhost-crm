package com.ayshriv.selfhostcrm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ayshriv.selfhostcrm.config.security.UserPrincipal;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

class RolePermissionsTest {

	@Test
	void adminHoldsEveryPermission() {
		assertThat(RolePermissions.of(Role.ADMIN)).containsExactlyInAnyOrder(Permission.values());
	}

	@Test
	void managerCanViewUpdateAndInviteButNotChangeRolesDeleteOrReadAudit() {
		assertThat(RolePermissions.of(Role.MANAGER)).containsExactlyInAnyOrder(Permission.USER_VIEW,
				Permission.USER_UPDATE, Permission.USER_INVITE, Permission.INVITATION_MANAGE);
	}

	@Test
	void memberHoldsNoPermission() {
		assertThat(RolePermissions.of(Role.MEMBER)).isEmpty();
	}

	@Test
	void principalExposesRoleAndOneAuthorityPerPermission() {
		for (Role role : Role.values()) {
			User user = new User(1L);
			user.setRole(role);
			user.setEmail("a@example.com");
			user.setPasswordHash("x");
			var names = new UserPrincipal(user).getAuthorities().stream().map(GrantedAuthority::getAuthority)
					.collect(Collectors.toSet());
			assertThat(names).contains("ROLE_" + role.name());
			assertThat(names).hasSize(1 + RolePermissions.of(role).size());
			RolePermissions.of(role).forEach(p -> assertThat(names).contains(p.name()));
		}
	}

}
