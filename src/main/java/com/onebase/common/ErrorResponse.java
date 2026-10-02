package com.onebase.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * The one shape every error leaves the API in.
 *
 * <p>The frontend's {@code apiFetch} reads exactly these fields ({@code status},
 * {@code error}, {@code message}, {@code path}, {@code fieldErrors}), so a screen
 * can show any failure the same way without knowing which endpoint produced it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
		Instant timestamp,
		int status,
		String error,
		String message,
		String path,
		Map<String, String> fieldErrors) {

	public static ErrorResponse of(int status, String error, String message, String path) {
		return new ErrorResponse(Instant.now(), status, error, message, path, null);
	}

	public static ErrorResponse validation(String message, String path, Map<String, String> fieldErrors) {
		return new ErrorResponse(Instant.now(), 400, "Bad Request", message, path, fieldErrors);
	}
}
