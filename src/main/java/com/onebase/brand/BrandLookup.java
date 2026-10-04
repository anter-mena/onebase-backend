package com.onebase.brand;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Reads a brand's website and finds what the Add form fills in: the name, the
 * four social links, and a logo.
 *
 * <p>It <b>suggests</b>; the Admin decides. Many sites block robots, link the
 * wrong account, or have no usable icon — so whatever is found is shown first
 * and can be corrected before saving, and a site that can't be read still lets
 * the Admin type everything in (see {@link Result#warning()}).
 *
 * <p>Downloads only through {@link SafeFetcher}. jsoup is used to read HTML
 * already downloaded, never to open a connection.
 */
@Component
class BrandLookup {

	/** Enough for the head and footer of any real home page. */
	static final int MAX_PAGE_BYTES = 2 * 1024 * 1024;
	static final int MAX_ICON_BYTES = 512 * 1024;
	private static final int MAX_ICON_TRIES = 5;

	private final SafeFetcher fetcher;

	BrandLookup(SafeFetcher fetcher) {
		this.fetcher = fetcher;
	}

	/** What was found. {@code warning} says what could not be, in a sentence for the Admin. */
	record Result(String name, Map<String, String> socials, byte[] logoPng, String warning) {
	}

	/** @throws UrlGuard.BlockedUrlException when the address (or a redirect) is not a public website */
	Result lookUp(BrandLinks.Website website) {
		String guessedName = nameFromDomain(website.domain());
		Optional<SafeFetcher.Fetched> page = fetcher.get(website.uri(), MAX_PAGE_BYTES, "text/html,application/xhtml+xml");
		if (page.isEmpty() || !page.get().contentType().contains("html")) {
			byte[] logo = fetchFirstLogo(fallbackIcons(website.uri())).orElse(null);
			return new Result(guessedName, Map.of(), logo,
				"The website couldn't be read (it may block robots). Check the name and add the links yourself.");
		}

		Parsed parsed = parse(decode(page.get()), page.get().finalUri());
		List<URI> icons = new ArrayList<>(parsed.icons());
		icons.addAll(fallbackIcons(page.get().finalUri()));
		byte[] logo = fetchFirstLogo(icons).orElse(null);

		String name = parsed.name() == null ? guessedName : parsed.name();
		String warning = logo == null ? "No logo could be found on the site. You can upload one." : null;
		return new Result(name, parsed.socials(), logo, warning);
	}

	// ── Reading the page ─────────────────────────────────────────────────

	record Parsed(String name, Map<String, String> socials, List<URI> icons) {
	}

	/** Separate so it can be tested on a saved page, without the internet. */
	static Parsed parse(String html, URI base) {
		Document doc = Jsoup.parse(html, base.toString());
		return new Parsed(findName(doc), findSocials(doc), findIcons(doc));
	}

	/** The site's own name for itself, in order of trust; null when nothing reads like a name. */
	private static String findName(Document doc) {
		for (String selector : List.of("meta[property=og:site_name]", "meta[name=application-name]",
				"meta[name=apple-mobile-web-app-title]")) {
			Element meta = doc.selectFirst(selector);
			if (meta != null && isName(meta.attr("content"))) return clean(meta.attr("content"));
		}
		String title = doc.title();
		if (title != null && !title.isBlank()) {
			// "Nike. Just Do It. Nike.com" / "Home | Adidas" → the shortest part that looks like a name.
			String best = null;
			for (String part : title.split("\\s+[|–—:·-]\\s+|\\.\\s+")) {
				String candidate = clean(part);
				if (isName(candidate) && !candidate.equalsIgnoreCase("home")
						&& (best == null || candidate.length() < best.length())) {
					best = candidate;
				}
			}
			return best;
		}
		return null;
	}

	private static boolean isName(String value) {
		return value != null && !value.isBlank() && value.trim().length() <= 60;
	}

	private static String clean(String value) {
		String trimmed = value.trim().replaceAll("\\s+", " ");
		// "Nike.com" → "Nike": a site naming itself by its address.
		if (trimmed.matches("[A-Za-z0-9-]+\\.(com|net|org|co|io|shop|store|app|fr|ma|es|de|uk|co\\.uk)")) {
			trimmed = trimmed.substring(0, trimmed.indexOf('.'));
		}
		return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
	}

	private static final Pattern URL_IN_TEXT = Pattern.compile("https?://[^\"'\\s<>]+");

	/**
	 * The first real account per network: links on the page, then the
	 * {@code sameAs} list sites publish for search engines. Share buttons and
	 * single posts are skipped (see {@link BrandLinks#social}).
	 */
	private static Map<String, String> findSocials(Document doc) {
		Set<String> candidates = new LinkedHashSet<>();
		for (Element link : doc.select("a[href]")) candidates.add(link.attr("abs:href"));
		for (Element script : doc.select("script[type=application/ld+json]")) {
			// JSON often writes "/" as "\/": undo that before looking for addresses.
			Matcher matcher = URL_IN_TEXT.matcher(script.data().replace("\\/", "/"));
			while (matcher.find()) candidates.add(matcher.group());
		}
		Map<String, String> found = new LinkedHashMap<>();
		for (String candidate : candidates) {
			for (String network : BrandLinks.NETWORKS) {
				if (found.containsKey(network)) continue;
				BrandLinks.social(network, candidate).ifPresent(link -> found.put(network, link));
			}
		}
		return found;
	}

	/** The site's own icons, best first: the large phone icon, then declared icons, biggest first. */
	private static List<URI> findIcons(Document doc) {
		record Icon(String href, int size, int rank) {
		}
		List<Icon> icons = new ArrayList<>();
		for (Element link : doc.select("link[rel][href]")) {
			String rel = link.attr("rel").toLowerCase(Locale.ROOT);
			String href = link.attr("abs:href");
			if (href.isBlank() || href.toLowerCase(Locale.ROOT).endsWith(".svg")
					|| link.attr("type").toLowerCase(Locale.ROOT).contains("svg")) {
				continue;
			}
			int size = largestSize(link.attr("sizes"));
			if (rel.contains("apple-touch-icon")) icons.add(new Icon(href, Math.max(size, 180), 0));
			else if (rel.contains("icon")) icons.add(new Icon(href, size, 1));
		}
		icons.sort(Comparator.comparingInt(Icon::rank).thenComparing(Comparator.comparingInt(Icon::size).reversed()));
		List<URI> uris = new ArrayList<>();
		for (Icon icon : icons) {
			try {
				uris.add(URI.create(icon.href()));
			} catch (IllegalArgumentException ignored) {
				// A broken address on their side: skip it.
			}
		}
		return uris;
	}

	private static int largestSize(String sizes) {
		int best = 0;
		for (String size : sizes.toLowerCase(Locale.ROOT).split("\\s+")) {
			String[] wh = size.split("x");
			if (wh.length == 2 && wh[0].matches("\\d{1,4}")) best = Math.max(best, Integer.parseInt(wh[0]));
		}
		return best;
	}

	/** Where most sites keep an icon even when the page doesn't say so. */
	private static List<URI> fallbackIcons(URI site) {
		return List.of(site.resolve("/apple-touch-icon.png"), site.resolve("/favicon.ico"));
	}

	/** The first icon that downloads safely and is a picture we can read. */
	private Optional<byte[]> fetchFirstLogo(List<URI> icons) {
		int tries = 0;
		for (URI icon : new LinkedHashSet<>(icons)) {
			if (tries++ >= MAX_ICON_TRIES) break;
			try {
				Optional<SafeFetcher.Fetched> fetched = fetcher.get(icon, MAX_ICON_BYTES, "image/*");
				if (fetched.isEmpty() || fetched.get().truncated()) continue;
				Optional<byte[]> png = LogoImages.toStoredPng(fetched.get().body());
				if (png.isPresent()) return png;
			} catch (UrlGuard.BlockedUrlException e) {
				// An icon pointing somewhere not allowed: try the next one.
			}
		}
		return Optional.empty();
	}

	private static String decode(SafeFetcher.Fetched page) {
		Matcher charset = Pattern.compile("charset=([\\w-]+)").matcher(page.contentType());
		Charset encoding = StandardCharsets.UTF_8;
		if (charset.find()) {
			try {
				encoding = Charset.forName(charset.group(1));
			} catch (IllegalArgumentException ignored) {
				// Unknown name: UTF-8 is right for nearly every site.
			}
		}
		return new String(page.body(), encoding);
	}

	/** "new-balance.com" → "New Balance". */
	static String nameFromDomain(String domain) {
		String label = domain.split("\\.")[0].replace('-', ' ');
		StringBuilder name = new StringBuilder();
		for (String word : label.split(" ")) {
			if (word.isEmpty()) continue;
			if (!name.isEmpty()) name.append(' ');
			name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return name.isEmpty() ? domain : name.toString();
	}
}
