package com.ayshriv.selfhostcrm.config.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BCrypt over a SHA-256 pre-hash. BCrypt only uses the first 72 bytes of its input (and recent
 * Spring Security versions reject longer input), but passwords may be up to 128 characters.
 * Pre-hashing keeps every character significant. Stored hashes are standard BCrypt strings.
 */
public class PrehashedBCryptPasswordEncoder implements PasswordEncoder {

	private final BCryptPasswordEncoder delegate = new BCryptPasswordEncoder();

	@Override
	public String encode(CharSequence rawPassword) {
		return delegate.encode(prehash(rawPassword));
	}

	@Override
	public boolean matches(CharSequence rawPassword, String encodedPassword) {
		if (rawPassword == null || encodedPassword == null) {
			return false;
		}
		return delegate.matches(prehash(rawPassword), encodedPassword);
	}

	private static String prehash(CharSequence raw) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(raw.toString().getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}

}
