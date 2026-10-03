package com.onebase.actionlog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Action log screen. Admins only — it is not in `SecurityConfig.COMMERCIAL_API`. */
@RestController
@RequestMapping("/api/action-log")
@Tag(name = "Action log", description = "Admins only: every change made in the workspace, and who made it")
public class ActionLogController {

	private final ActionLogService actionLog;

	public ActionLogController(ActionLogService actionLog) {
		this.actionLog = actionLog;
	}

	@GetMapping
	@Operation(summary = "The latest entries, newest first", description = "At most 1000 at a time.")
	public List<ActionLogService.Entry> latest(@RequestParam(defaultValue = "1000") int limit) {
		return actionLog.latest(limit);
	}
}
