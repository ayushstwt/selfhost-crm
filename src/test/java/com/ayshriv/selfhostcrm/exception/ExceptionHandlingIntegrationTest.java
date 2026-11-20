package com.ayshriv.selfhostcrm.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ayshriv.selfhostcrm.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

@Import({ TestcontainersConfiguration.class, ExceptionHandlingIntegrationTest.OpenTestChain.class })
@SpringBootTest
@AutoConfigureMockMvc
class ExceptionHandlingIntegrationTest {

	@TestConfiguration
	static class OpenTestChain {

		@Bean
		@Order(Ordered.HIGHEST_PRECEDENCE)
		SecurityFilterChain testChain(HttpSecurity http) {
			http.securityMatcher("/api/test/**", "/web-missing/**")
					.authorizeHttpRequests(a -> a.anyRequest().permitAll())
					.csrf(c -> c.disable());
			return http.build();
		}

	}

	@Autowired
	private MockMvc mvc;

	private ListAppender<ILoggingEvent> appender;

	private Logger handlerLogger;

	@BeforeEach
	void attachAppender() {
		handlerLogger = (Logger) LoggerFactory.getLogger(ControllerExceptionHandler.class);
		appender = new ListAppender<>();
		appender.start();
		handlerLogger.addAppender(appender);
	}

	@AfterEach
	void detachAppender() {
		handlerLogger.detachAppender(appender);
	}

	@Test
	void validationReturnsPerFieldErrors() throws Exception {
		mvc.perform(post("/api/test/validate").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"\",\"email\":\"not-an-email\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value("error"))
				.andExpect(jsonPath("$.errorCode").value("E005"))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(jsonPath("$.errors[?(@.field=='name')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='email')]").exists())
				.andExpect(jsonPath("$.path").value("/api/test/validate"))
				.andExpect(jsonPath("$.timestamp").exists());
	}

	@Test
	void baseExceptionUsesEnumStatusAndIsLoggedAtWarn() throws Exception {
		mvc.perform(get("/api/test/notfound/1"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("E001"));
		assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.WARN);
		assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
	}

	@Test
	void constraintViolationFallsBackToE005() throws Exception {
		mvc.perform(get("/api/test/positive").param("n", "-1"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("E005"));
	}

	@Test
	void constraintViolationWithEnumMessageUsesThatCode() throws Exception {
		mvc.perform(get("/api/test/positive-enum").param("n", "-1"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errorCode").value("E004"));
	}

	@Test
	void methodNotAllowedIs405() throws Exception {
		mvc.perform(post("/api/test/post-only"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.errorCode").value("E405"))
				.andExpect(jsonPath("$.status").value("error"));
	}

	@Test
	void unsupportedMediaTypeIs415() throws Exception {
		mvc.perform(post("/api/test/json-only").contentType(MediaType.TEXT_PLAIN).content("x"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.errorCode").value("E415"));
	}

	@Test
	void missingParameterIs400() throws Exception {
		mvc.perform(get("/api/test/param"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value("error"));
	}

	@Test
	void typeMismatchIs400() throws Exception {
		mvc.perform(get("/api/test/notfound/abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value("error"));
	}

	@Test
	void unreadableJsonIs400() throws Exception {
		mvc.perform(post("/api/test/validate").contentType(MediaType.APPLICATION_JSON).content("{broken"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value("error"));
	}

	@Test
	void unknownApiRouteIs404InEnvelope() throws Exception {
		mvc.perform(get("/api/test/does-not-exist"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value("error"))
				.andExpect(jsonPath("$.errorCode").value("E001"));
	}

	@Test
	void unknownWebRouteRendersHtmlWithoutStackTrace() throws Exception {
		mvc.perform(get("/web-missing/page"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
				.andExpect(content().string(not(containsString("Exception"))));
	}

	@Test
	void catchAllDoesNotLeakMessageAndIsLoggedAtError() throws Exception {
		mvc.perform(get("/api/test/boom"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.errorCode").value("E500"))
				.andExpect(content().string(not(containsString("secret-internal-detail"))));
		assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.ERROR && e.getThrowableProxy() != null);
	}

	@Test
	void securityExceptionsAreNotSwallowedByCatchAll() throws Exception {
		mvc.perform(get("/api/test/denied"))
				.andExpect(status().isForbidden());
		assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
	}

}
