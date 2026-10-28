package com.onebase.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One week of One Base, Sunday to Saturday (decided 2026-10-28), compared with the
 * week before: what the weekly email says, as numbers. USD, business time zone.
 *
 * @param sites null when Google Analytics did not answer; empty when no brand has a GA4 property
 */
public record WeeklyReport(
		LocalDate start,
		LocalDate end,
		LocalDate previousStart,
		LocalDate previousEnd,
		int isoWeek,
		Money money,
		Money previousMoney,
		List<Line> brands,
		List<Line> accounts,
		Clients clients,
		Attention attention,
		Credit credit,
		Activity activity,
		List<Site> sites,
		Instant generatedAt) {

	/** Cash, as the Ledger counts it: money in, minus the top-ups and the perks paid. */
	public record Money(int payments, int newPlans, BigDecimal newPlansAmount, int renewals, BigDecimal renewalsAmount,
			BigDecimal moneyIn, BigDecimal topups, BigDecimal perks, BigDecimal net, BigDecimal average) {
	}

	/** A named total: a brand, an account, a team member. */
	public record Line(String name, int count, BigDecimal amount) {
	}

	/**
	 * @param activeNow clients with paid time on the week's last day
	 * @param activeBefore the same, on the last day of the week before
	 * @param ended clients whose paid time ran out during the week
	 */
	public record Clients(int newClients, int fromWhatsApp, int byHand, int becameActive, long activeNow, long activeBefore,
			int ended) {
	}

	/** As Renewals shows it when the report is made: ending within 10 days, and callbacks. Soonest first. */
	public record Attention(int endingSoon, int callbacks, List<Person> people, int more) {
	}

	/** @param endsOn null for a callback (a trial that ended) */
	public record Person(String name, String brand, LocalDate endsOn, boolean callback) {
	}

	public record Credit(long left, long used, long toppedUpCredits, BigDecimal toppedUpAmount) {
	}

	/** @param brandEmails null when Gmail did not answer */
	public record Activity(long whatsappMessages, long whatsappConversations, Integer brandEmails, List<Line> paymentsBy) {
	}

	public record Site(String brand, long sessions, long previousSessions) {
	}
}
