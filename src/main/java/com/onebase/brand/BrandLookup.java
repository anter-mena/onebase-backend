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
 * four social links, and the address of its logo.
 *
 * <p>It <b>suggests</b>; the Admin decides. Many sites block robots, link the
 * wrong account, or have no usable icon — so whatever is found is shown first
 * and can be corrected before saving, and a site that can't be read still lets
 * the Admin type everything in (see {@link Result#warning()}).
 *
 * <p>The logo is not downloaded or kept (decided 2026-10-16): we keep its
 * https address on the brand's site, and screens and emails load it from there.
 * A PNG/JPEG (what emails can show) is preferred over an ICO or SVG.
 *
 * <p>Opens addresses only through {@link SafeFetcher}. jsoup is used to read HTML
 * already downloaded, never to open a connection.
 */
@Component
class BrandLookup {

	/** Enough for the head and footer of any real home page. */
	static final int MAX_PAGE_BYTES = 2 * 1024 * 1024;
	/** An icon is only asked for to check that it is there: a couple of bytes are read, then the line is closed. */
	private static final int CHECK_BYTES = 1;
	private static final int MAX_ICON_TRIES = 6;
	/** The size of the {@code brands.logo_url} column. */
	static final int MAX_LOGO_URL = 2000;

	private final SafeFetcher fetcher;

	BrandLookup(SafeFetcher fetcher) {
		this.fetcher = fetcher;
	}

	/** What was found. {@code warning} says what could not be, in a sentence for the Admin. */
	record Result(String name, Map<String, String> socials, String logoUrl, String warning) {
	}

	/** @throws UrlGuard.BlockedUrlException when the address (or a redirect) is not a public website */
	Result lookUp(BrandLinks.Website website) {
		String guessedName = nameFromDomain(website.domain());
		Optional<SafeFetcher.Fetched> page = fetcher.get(website.uri(), MAX_PAGE_BYTES, "text/html,application/xhtml+xml");
		if (page.isEmpty() || !page.get().contentType().contains("html")) {
			String logo = firstWorkingLogo(candidates(List.of(), website.uri())).orElse(null);
			return new Result(guessedName, Map.of(), logo,
				"The website couldn't be read (it may block robots). Check the name and add the links yourself.");
		}

		Parsed parsed = parse(decode(page.get()), page.get().finalUri());
		String logo = firstWorkingLogo(candidates(parsed.icons(), page.get().finalUri())).orElse(null);

		String name = parsed.name() == null ? guessedName : parsed.name();
		String warning = logo == null ? "No logo could be found on the site. You can paste its address." : null;
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

	/**
	 * The site's own logos and icons, best first. PNG/JPEG/GIF/WebP before anything
	 * else (ICO, SVG, no extension), since emails can't show the others; within each,
	 * the large phone icon, a declared logo, the declared icons biggest first, and
	 * last the page's share picture ({@code og:image}).
	 */
	private static List<URI> findIcons(Document doc) {
		record Icon(String href, int size, int rank) {
		}
		List<Icon> icons = new ArrayList<>();
		for (Element link : doc.select("link[rel][href]")) {
			String rel = link.attr("rel").toLowerCase(Locale.ROOT);
			String href = link.attr("abs:href");
			if (href.isBlank()) continue;
			int size = largestSize(link.attr("sizes"));
			if (rel.contains("apple-touch-icon")) icons.add(new Icon(href, Math.max(size, 180), 0));
			else if (rel.contains("icon")) icons.add(new Icon(href, size, 2));
		}
		for (Element meta : doc.select("meta[property=og:logo][content], meta[itemprop=logo][content]")) {
			icons.add(new Icon(meta.attr("abs:content"), 0, 1));
		}
		for (Element meta : doc.select("meta[property=og:image][content], meta[property=og:image:secure_url][content]")) {
			icons.add(new Icon(meta.attr("abs:content"), 0, 3));
		}
		icons.sort(Comparator.comparing((Icon icon) -> !Brand.isPicture(icon.href()))
			.thenComparingInt(Icon::rank)
			.thenComparing(Comparator.comparingInt(Icon::size).reversed()));
		List<URI> uris = new ArrayList<>();
		for (Icon icon : icons) {
			if (icon.href().isBlank()) continue;
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

	/**
	 * What to try, best first: the page's PNG/JPEG logos, then where most sites keep
	 * one even when the page doesn't say so, then the page's ICO/SVG icons, and last
	 * {@code /favicon.ico}.
	 */
	static List<URI> candidates(List<URI> pageIcons, URI site) {
		List<URI> ordered = new ArrayList<>();
		for (URI icon : pageIcons) if (Brand.isPicture(icon.toString())) ordered.add(icon);
		ordered.add(site.resolve("/apple-touch-icon.png"));
		for (URI icon : pageIcons) if (!Brand.isPicture(icon.toString())) ordered.add(icon);
		ordered.add(site.resolve("/favicon.ico"));
		return ordered;
	}

	/**
	 * The https address of the first candidate that answers with a picture. Only
	 * https is kept — the app and mail apps refuse plain http pictures — so an
	 * {@code http://} icon is asked for over https. Nothing is downloaded beyond
	 * the first bytes, and nothing is kept but the address.
	 */
	private Optional<String> firstWorkingLogo(List<URI> icons) {
		int tries = 0;
		for (URI icon : new LinkedHashSet<>(icons)) {
			URI https = toHttps(icon);
			if (https == null || https.toString().length() > MAX_LOGO_URL) continue;
			if (tries++ >= MAX_ICON_TRIES) break;
			try {
				Optional<SafeFetcher.Fetched> fetched = fetcher.get(https, CHECK_BYTES, "image/*");
				// A missing icon often comes back as the site's own "not found" page, with a 200.
				if (fetched.isPresent() && !fetched.get().contentType().contains("html")
						&& !fetched.get().contentType().startsWith("text/")) {
					return Optional.of(https.toString());
				}
			} catch (UrlGuard.BlockedUrlException e) {
				// An icon pointing somewhere not allowed: try the next one.
			}
		}
		return Optional.empty();
	}

	private static URI toHttps(URI uri) {
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (scheme.equals("https")) return uri;
		if (!scheme.equals("http") || uri.getHost() == null) return null;
		if (uri.getPort() != -1 && uri.getPort() != 80) return null;
		String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
		try {
			return URI.create("https://" + uri.getHost() + path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
		} catch (IllegalArgumentException e) {
			return null;
		}
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
