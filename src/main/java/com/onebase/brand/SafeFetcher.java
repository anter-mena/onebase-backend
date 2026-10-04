package com.onebase.brand;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The only way the backend downloads anything from an address someone typed.
 *
 * <p>Every rule of the security layer is here or in {@link UrlGuard}:
 * <ul>
 *   <li>each address is checked before it is opened — <b>including every
 *       redirect</b>, which is followed by hand (an allowed site could
 *       otherwise redirect us to an internal one);</li>
 *   <li>at most {@link #MAX_REDIRECTS} redirects;</li>
 *   <li>short timeouts, so a slow site can't hold a request open;</li>
 *   <li>a size limit, enforced while reading — a huge or endless answer is cut
 *       off instead of filling the memory.</li>
 * </ul>
 */
@Component
class SafeFetcher {

	static final int MAX_REDIRECTS = 4;
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(4);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(6);
	/** Looks like a browser enough that most sites answer, and says who we are. */
	private static final String USER_AGENT =
		"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36 OneBase/1.0";

	private final HttpClient http = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NEVER)
		.connectTimeout(CONNECT_TIMEOUT)
		.build();

	/** What came back, and from where after any redirects. */
	record Fetched(URI finalUri, String contentType, byte[] body, boolean truncated) {
	}

	/**
	 * Downloads at most {@code maxBytes}. Empty when the site answered with an
	 * error, could not be reached, or (for images) sent too much.
	 *
	 * @throws UrlGuard.BlockedUrlException when an address on the way is not allowed
	 */
	Optional<Fetched> get(URI start, int maxBytes, String accept) {
		URI uri = start;
		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			UrlGuard.check(uri);
			HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(READ_TIMEOUT)
				.header("User-Agent", USER_AGENT)
				.header("Accept", accept)
				.header("Accept-Language", "en")
				.GET()
				.build();
			HttpResponse<InputStream> response;
			try {
				response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
			} catch (IOException e) {
				return Optional.empty();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return Optional.empty();
			}

			int status = response.statusCode();
			if (status >= 300 && status < 400) {
				closeQuietly(response.body());
				Optional<String> location = response.headers().firstValue("Location");
				if (location.isEmpty()) return Optional.empty();
				try {
					uri = uri.resolve(location.get().trim());
				} catch (IllegalArgumentException e) {
					return Optional.empty();
				}
				continue;
			}
			if (status != 200) {
				closeQuietly(response.body());
				return Optional.empty();
			}

			try (InputStream body = response.body()) {
				byte[] bytes = body.readNBytes(maxBytes + 1);
				boolean truncated = bytes.length > maxBytes;
				String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
				return Optional.of(new Fetched(uri, type, truncated ? Arrays.copyOf(bytes, maxBytes) : bytes, truncated));
			} catch (IOException e) {
				return Optional.empty();
			}
		}
		return Optional.empty(); // too many redirects
	}

	private static void closeQuietly(InputStream stream) {
		try {
			stream.close();
		} catch (IOException ignored) {
			// Nothing to do: we are not reading it.
		}
	}
}
