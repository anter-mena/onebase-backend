package com.onebase.report;

import com.onebase.common.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The weekly report, for n8n (2026-10-28): it fetches this every Sunday 00:00 (Toronto)
 * and sends {@code html} with {@code subject} by email.
 *
 * <p>No account: a machine asks, so it proves itself with its own key in
 * {@code X-Report-Key} (REPORT_API_KEY on the server), compared in constant time. That
 * key opens this one address and nothing else. Without a key set on the server, the
 * address does not exist.
 */
@RestController
@RequestMapping("/api/reports")
@Tag(name = "Reports", description = "The weekly report email, for the n8n workflow. Its own key, no account.")
public class WeeklyReportController {

	public static final String KEY_HEADER = "X-Report-Key";

	private final WeeklyReportService service;
	private final byte[] key;
	private final String appUrl;

	public WeeklyReportController(WeeklyReportService service, @Value("${onebase.report.key:}") String key,
			@Value("${onebase.frontend-url}") String appUrl) {
		this.service = service;
		this.key = key.isBlank() ? null : key.getBytes(StandardCharsets.UTF_8);
		this.appUrl = appUrl.replaceAll("/+$", "");
	}

	/** What n8n sends: the subject line, the email, and the numbers behind it. */
	public record WeeklyEmail(String subject, String html, LocalDate start, LocalDate end, WeeklyReport report) {
	}

	@GetMapping("/weekly")
	@Operation(summary = "The weekly report email (n8n)", description = "Header X-Report-Key. week: any day of the Sunday–Saturday week to report "
		+ "(default: the last one finished). format=html returns the email alone, to look at it.")
	public ResponseEntity<?> weekly(@RequestHeader(name = KEY_HEADER, required = false) String given,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
			@RequestParam(defaultValue = "json") String format) {
		if (key == null) throw ApiException.notFound("Nothing lives at this address.");
		if (given == null || !MessageDigest.isEqual(key, given.getBytes(StandardCharsets.UTF_8))) {
			throw ApiException.unauthorized("This address needs the report key.");
		}
		WeeklyReport report = service.report(week);
		String html = WeeklyReportEmail.html(report, appUrl);
		if (format.equalsIgnoreCase("html")) {
			return ResponseEntity.ok().contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)).body(html);
		}
		return ResponseEntity.ok(new WeeklyEmail(WeeklyReportEmail.subject(report), html, report.start(), report.end(), report));
	}
}
