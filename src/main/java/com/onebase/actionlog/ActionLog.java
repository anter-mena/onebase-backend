package com.onebase.actionlog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One line of the Action log (table {@code action_logs}). Written once, never
 * changed or deleted — an audit trail that can be edited is not one.
 *
 * <p>The actor's name is copied in, not looked up: the log says who did it as
 * they were called then, even after a rename.
 */
@Entity
@Table(name = "action_logs")
public class ActionLog {

	public enum Action { CREATED, UPDATED, ACTIVATED, DEACTIVATED, DELETED, EXPORTED, SIGNED_IN }

	public enum TargetType { CLIENT, BRAND, PAYMENT_METHOD, SUBSCRIPTION, WORKSPACE, USER, PERK, PANEL_CREDIT }

	public enum Source { WEB, MOBILE, API }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id")
	private Long userId;

	@Column(name = "actor_name", nullable = false)
	private String actorName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Action action;

	@Enumerated(EnumType.STRING)
	@Column(name = "target_type", nullable = false)
	private TargetType targetType;

	@Column(name = "target_id")
	private Long targetId;

	@Column(name = "target_name", nullable = false)
	private String targetName;

	private String detail;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Source source = Source.WEB;

	private String ip;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	protected ActionLog() {
	}

	ActionLog(Long userId, String actorName, Action action, TargetType targetType, Long targetId,
			String targetName, String detail, String ip) {
		this.userId = userId;
		this.actorName = cut(actorName, 120);
		this.action = action;
		this.targetType = targetType;
		this.targetId = targetId;
		this.targetName = cut(targetName, 150);
		this.detail = cut(detail, 500);
		this.ip = ip;
	}

	private static String cut(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}

	public Long getId() { return id; }
	public Long getUserId() { return userId; }
	public String getActorName() { return actorName; }
	public Action getAction() { return action; }
	public TargetType getTargetType() { return targetType; }
	public Long getTargetId() { return targetId; }
	public String getTargetName() { return targetName; }
	public String getDetail() { return detail; }
	public Source getSource() { return source; }
	public String getIp() { return ip; }
	public Instant getCreatedAt() { return createdAt; }
}
