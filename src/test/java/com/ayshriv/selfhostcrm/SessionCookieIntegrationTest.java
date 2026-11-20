package com.ayshriv.selfhostcrm;

import static org.assertj.core.api.Assertions.assertThat;

import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.repository.UserRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Runs against a real servlet container so the session cookie flags can be observed. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionCookieIntegrationTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder encoder;

	@Test
	void sessionCookieIsHttpOnlyAndSameSiteLax() throws Exception {
		jdbc.execute("delete from user_log");
		jdbc.execute("delete from refresh_tokens");
		jdbc.execute("delete from users");
		User u = new User();
		u.setEmail("cookie@example.com");
		u.setDisplayName("Cookie");
		u.setPasswordHash(encoder.encode("correct horse battery"));
		u.setRole(Role.MEMBER);
		users.saveAndFlush(u);

		HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
		String base = "http://localhost:" + port;
		HttpResponse<String> page = client.send(HttpRequest.newBuilder(URI.create(base + "/login")).build(),
				HttpResponse.BodyHandlers.ofString());
		Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(page.body());
		assertThat(m.find()).isTrue();
		String sessionCookie = page.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];

		String form = "username=" + URLEncoder.encode("cookie@example.com", StandardCharsets.UTF_8)
				+ "&password=" + URLEncoder.encode("correct horse battery", StandardCharsets.UTF_8) + "&_csrf="
				+ URLEncoder.encode(m.group(1), StandardCharsets.UTF_8);
		HttpResponse<String> login = client.send(HttpRequest.newBuilder(URI.create(base + "/login"))
				.header("Content-Type", "application/x-www-form-urlencoded").header("Cookie", sessionCookie)
				.POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());

		assertThat(login.statusCode()).isEqualTo(302);
		List<String> cookies = login.headers().allValues("Set-Cookie");
		String all = String.join(" | ", cookies);
		// session fixation protection issues a fresh session id at login
		assertThat(all).contains("JSESSIONID");
		assertThat(all).doesNotContain(sessionCookie.split("=")[1]);
		assertThat(all).containsIgnoringCase("HttpOnly");
		assertThat(all).containsIgnoringCase("SameSite=Lax");
		assertThat(all).doesNotContainIgnoringCase("Secure;").doesNotContainIgnoringCase("; Secure");
	}

}
