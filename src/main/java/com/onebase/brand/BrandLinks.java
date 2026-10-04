package com.onebase.brand;

import com.onebase.common.ApiException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The rules for the addresses a brand is made of.
 *
 * <p><b>Website.</b> "nike.com", "www.nike.com", "https://NIKE.com/en/" all
 * give the domain {@code nike.com} — what makes two links the same brand (the
 * database refuses a second one) — and the website {@code https://www.nike.com}
 * (the host as typed, no path).
 *
 * <p><b>Social links.</b> Only the four networks, only on their own sites, and
 * always rewritten to one clean form ({@code https://www.instagram.com/nike}).
 * That is also a safety rule: a stored link is drawn as a clickable link, so
 * nothing else — a {@code javascript:} address, a look-alike site — gets in.
 */
final class BrandLinks {

	private BrandLinks() {
	}

	static final List<String> NETWORKS = List.of("instagram", "facebook", "x", "tiktok");

	/** A brand's website, as typed, made clean. */
	record Website(String domain, String url, URI uri) {
	}

	/** Reads what the Admin typed or pasted; 400 with a readable sentence when it is not a website. */
	static Website website(String input) {
		String text = input == null ? "" : input.trim();
		if (text.isEmpty()) throw ApiException.badRequest("Enter the brand's website, like nike.com.");
		if (!text.contains("://")) text = "https://" + text;
		URI uri;
		try {
			uri = new URI(text);
		} catch (URISyntaxException e) {
			throw ApiException.badRequest("This is not a website address. Try something like nike.com.");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
			throw ApiException.badRequest("This is not a website address. Try something like nike.com.");
		}
		String host;
		try {
			host = UrlGuard.asciiHost(uri.getHost());
		} catch (UrlGuard.BlockedUrlException e) {
			throw ApiException.badRequest(e.getMessage());
		}
		if (!host.matches("[a-z0-9-]+(\\.[a-z0-9-]+)+") || host.length() > 200) {
			throw ApiException.badRequest("This is not a website address. Try something like nike.com.");
		}
		String domain = host.startsWith("www.") ? host.substring(4) : host;
		String url = "https://" + host;
		return new Website(domain, url, URI.create(scheme + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + "/"));
	}

	private static final Map<String, Set<String>> HOSTS = Map.of(
		"instagram", Set.of("instagram.com"),
		"facebook", Set.of("facebook.com", "fb.com"),
		"x", Set.of("x.com", "twitter.com"),
		"tiktok", Set.of("tiktok.com"));

	private static final Map<String, String> CANONICAL = Map.of(
		"instagram", "https://www.instagram.com/",
		"facebook", "https://www.facebook.com/",
		"x", "https://x.com/",
		"tiktok", "https://www.tiktok.com/");

	/** Paths that are not an account: sharing buttons, single posts, help pages. */
	private static final Set<String> NOT_AN_ACCOUNT = Set.of(
		"share", "sharer", "sharer.php", "intent", "home", "p", "reel", "reels", "explore", "stories", "tv",
		"hashtag", "search", "login", "signup", "dialog", "plugins", "tr", "watch", "video", "status", "i",
		"policies", "legal", "about", "help", "privacy", "terms", "embed", "discover", "tag", "music",
		"accounts", "pages", "groups", "events", "photo", "photo.php", "profile.php", "people");

	/**
	 * The clean account link for this network, or empty when the address is not
	 * an account on it. Used both for what a site links to, and for what an
	 * Admin types (where empty means "refuse").
	 */
	static Optional<String> social(String network, String input) {
		if (input == null || input.isBlank()) return Optional.empty();
		String text = input.trim();
		if (!text.contains("://")) text = "https://" + text;
		URI uri;
		try {
			uri = new URI(text);
		} catch (URISyntaxException e) {
			return Optional.empty();
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if ((!scheme.equals("https") && !scheme.equals("http")) || uri.getHost() == null) return Optional.empty();
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		for (String prefix : List.of("www.", "m.", "mobile.", "web.")) {
			if (host.startsWith(prefix)) host = host.substring(prefix.length());
		}
		if (!HOSTS.getOrDefault(network, Set.of()).contains(host)) return Optional.empty();

		String path = uri.getPath() == null ? "" : uri.getPath();
		String[] parts = path.split("/");
		String first = "";
		for (String part : parts) {
			if (!part.isBlank()) {
				first = part;
				break;
			}
		}
		if (first.isEmpty() || NOT_AN_ACCOUNT.contains(first.toLowerCase(Locale.ROOT))) return Optional.empty();
		if (network.equals("tiktok") && !first.startsWith("@")) return Optional.empty();
		if (!first.matches("@?[A-Za-z0-9._-]{1,60}")) return Optional.empty();
		return Optional.of(CANONICAL.get(network) + first);
	}

	/** The four links an Admin typed, each made clean; 400 naming the one that is not an account. */
	static Map<String, String> socials(Map<String, String> input) {
		Map<String, String> clean = new java.util.LinkedHashMap<>();
		if (input == null) return clean;
		for (String network : NETWORKS) {
			String value = input.get(network);
			if (value == null || value.isBlank()) continue;
			String link = social(network, value).orElseThrow(() -> ApiException.badRequest(
				"The " + label(network) + " link is not an account on " + label(network) + "."));
			clean.put(network, link);
		}
		return clean;
	}

	static String label(String network) {
		return switch (network) {
			case "instagram" -> "Instagram";
			case "facebook" -> "Facebook";
			case "x" -> "X";
			default -> "TikTok";
		};
	}
}
