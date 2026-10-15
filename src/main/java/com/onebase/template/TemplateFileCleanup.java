package com.onebase.template;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes the template files nothing uses any more, once a template save has committed:
 * the files that template just let go of, and any upload left unused for more than an
 * hour (a builder closed without saving). A fresh upload is kept: someone may be about
 * to save it.
 *
 * <p>Runs after the commit, in its own transaction, so a failure here never undoes the
 * save; the next save tries again.
 */
@Component
class TemplateFileCleanup {

	private static final Logger log = LoggerFactory.getLogger(TemplateFileCleanup.class);
	static final Duration ABANDONED_AFTER = Duration.ofHours(1);

	/** Published by {@link TemplateService} on each save, with the files the template used before. */
	record TemplateSaved(Set<Long> previousFileIds) {
	}

	private final TemplateFileRepository files;
	private final TransactionTemplate transaction;

	TemplateFileCleanup(TemplateFileRepository files, PlatformTransactionManager transactions) {
		this.files = files;
		this.transaction = new TransactionTemplate(transactions);
		// The saved template's transaction is over but still bound to the thread: a new one, on purpose.
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void afterSave(TemplateSaved event) {
		try {
			int deleted = transaction.execute(status -> {
				int n = event.previousFileIds().isEmpty() ? 0 : files.deleteUnusedAmong(event.previousFileIds());
				return n + files.deleteUnusedOlderThan(Instant.now().minus(ABANDONED_AFTER));
			});
			if (deleted > 0) log.info("Deleted {} template file(s) no template uses", deleted);
		} catch (RuntimeException e) {
			log.warn("Could not delete unused template files: {}", e.getMessage());
		}
	}
}
