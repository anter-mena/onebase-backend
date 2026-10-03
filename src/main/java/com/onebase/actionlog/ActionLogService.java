package com.onebase.actionlog;

import com.onebase.actionlog.ActionLog.Action;
import com.onebase.actionlog.ActionLog.TargetType;
import com.onebase.common.RequestInfo;
import com.onebase.user.User;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes and reads the Action log.
 *
 * <p>{@link #record} joins the caller's transaction on purpose: if the change
 * itself fails and rolls back, its log line goes with it — the log never claims
 * something happened that did not. The caller's address is read from the
 * request here, so services do not have to pass it along.
 */
@Service
public class ActionLogService {

	/** The most the screen loads at once; it filters and pages them in the browser. */
	static final int MAX_ROWS = 1000;

	private final ActionLogRepository logs;

	public ActionLogService(ActionLogRepository logs) {
		this.logs = logs;
	}

	@Transactional
	public void record(User actor, Action action, TargetType targetType, Long targetId, String targetName, String detail) {
		logs.save(new ActionLog(actor.getId(), actor.getFullName(), action, targetType, targetId, targetName, detail,
			RequestInfo.clientIp()));
	}

	/** Something done to (or by) a user account. */
	@Transactional
	public void recordUser(User actor, Action action, User target, String detail) {
		record(actor, action, TargetType.USER, target.getId(), target.getFullName(), detail);
	}

	@Transactional(readOnly = true)
	public List<Entry> latest(int limit) {
		int size = Math.max(1, Math.min(limit, MAX_ROWS));
		return logs.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, size)).stream().map(Entry::from).toList();
	}

	/** One row of the Action log screen. */
	public record Entry(long id, Instant at, Long userId, String actor, Action action, TargetType targetType,
			Long targetId, String targetName, String detail, ActionLog.Source source, String ip) {

		static Entry from(ActionLog log) {
			return new Entry(log.getId(), log.getCreatedAt(), log.getUserId(), log.getActorName(), log.getAction(),
				log.getTargetType(), log.getTargetId(), log.getTargetName(), log.getDetail(), log.getSource(), log.getIp());
		}
	}
}
