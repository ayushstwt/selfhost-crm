package com.ayshriv.selfhostcrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.exception.ResourceNotFoundException;
import com.ayshriv.selfhostcrm.service.SetupService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@SpringBootTest
@AutoConfigureMockMvc
class SetupIntegrationTest {

	private static final String PASSWORD = "correct horse battery";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	SetupService setupService;

	@Autowired
	PasswordEncoder encoder;

	@BeforeEach
	void clean() {
		jdbc.execute("delete from user_log");
		jdbc.execute("delete from invitations");
		jdbc.execute("delete from refresh_tokens");
		jdbc.execute("delete from users");
	}

	private MockHttpServletRequestBuilder submit(String password, String confirm) {
		return post("/setup").with(csrf()).param("displayName", "Root Admin").param("email", "Admin@Example.com")
				.param("password", password).param("confirmPassword", confirm);
	}

	private long userCount() {
		return jdbc.queryForObject("select count(*) from users", Long.class);
	}

	@Test
	void webRoutesRedirectToSetupWhenNoUsers() throws Exception {
		mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/setup"));
		mvc.perform(get("/login")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/setup"));
		mvc.perform(get("/setup")).andExpect(status().isOk());
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void setupPageWiresCsrfIntoHtmxOnBody() throws Exception {
		mvc.perform(get("/setup")).andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers
						.containsString("<body hx-headers=\"{&quot;X-CSRF-TOKEN&quot;:&quot;")));
	}

	@Test
	void firstAdminIsCreatedOnceAndSetupThen404s() throws Exception {
		mvc.perform(submit(PASSWORD, PASSWORD)).andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login?setup"));

		assertThat(userCount()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select role from users", String.class)).isEqualTo(Role.ADMIN.name());
		assertThat(jdbc.queryForObject("select email from users", String.class)).isEqualTo("admin@example.com");
		String hash = jdbc.queryForObject("select password_hash from users", String.class);
		assertThat(hash).startsWith("$2").isNotEqualTo(PASSWORD);
		assertThat(encoder.matches(PASSWORD, hash)).isTrue();
		assertThat(jdbc.queryForObject("select action || '/' || sub_action from user_log", String.class))
				.isEqualTo("SETUP_COMPLETED/SUCCESS");

		mvc.perform(get("/setup")).andExpect(status().isNotFound());
		mvc.perform(submit(PASSWORD, PASSWORD)).andExpect(status().isNotFound());
		assertThat(userCount()).isEqualTo(1);
		// no longer redirected to /setup
		mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
	}

	@Test
	void validationErrorsKeepSetupOpen() throws Exception {
		mvc.perform(submit("short", "short")).andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("12 to 128")));
		mvc.perform(submit(PASSWORD, "different password!")).andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Passwords do not match")));
		mvc.perform(post("/setup").with(csrf()).param("displayName", "A").param("email", "same-as-pass@example.com")
				.param("password", "same-as-pass@example.com").param("confirmPassword", "same-as-pass@example.com"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("must not be the same")));
		assertThat(userCount()).isZero();
	}

	@Test
	void csrfIsRequired() throws Exception {
		mvc.perform(post("/setup").param("displayName", "A").param("email", "a@example.com")
				.param("password", PASSWORD).param("confirmPassword", PASSWORD)).andExpect(status().isForbidden());
		assertThat(userCount()).isZero();
	}

	@Test
	void concurrentSetupCreatesExactlyOneAdmin() throws Exception {
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Boolean>> results = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			int n = i;
			Callable<Boolean> task = () -> {
				ready.countDown();
				go.await();
				try {
					setupService.createFirstAdmin("Admin " + n, "admin" + n + "@example.com", PASSWORD);
					return true;
				}
				catch (ResourceNotFoundException e) {
					return false;
				}
			};
			results.add(pool.submit(task));
		}
		ready.await();
		go.countDown();
		int successes = 0;
		for (Future<Boolean> f : results) {
			if (f.get()) {
				successes++;
			}
		}
		pool.shutdown();
		assertThat(successes).isEqualTo(1);
		assertThat(userCount()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from user_log", Long.class)).isEqualTo(1);
	}

}
