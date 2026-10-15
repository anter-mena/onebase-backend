package com.onebase.template;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A saved WhatsApp message (table {@code message_templates}), sent from the Inbox as
 * normal messages. There is no delete: a template is switched off instead.
 *
 * <p>{@code updatedAt} moves with the content only (name, language, footer, message),
 * never with the on/off switch.
 */
@Entity
@Table(name = "message_templates")
public class MessageTemplate {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	/** EN, FR or AR. */
	@Column(nullable = false)
	private String language;

	@Column(nullable = false)
	private boolean active = true;

	@Column(nullable = false)
	private String footer = "";

	/** The message in order; the list index is the block's {@code position}. */
	@ElementCollection
	@CollectionTable(name = "message_template_blocks", joinColumns = @JoinColumn(name = "template_id"))
	@OrderColumn(name = "position", nullable = false)
	private List<TemplateBlock> blocks = new ArrayList<>();

	@Column(name = "created_by")
	private Long createdBy;

	@Column(name = "updated_by")
	private Long updatedBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected MessageTemplate() {
	}

	MessageTemplate(long createdBy) {
		this.createdBy = createdBy;
		this.updatedBy = createdBy;
	}

	/** Name, language, footer and message, all at once (the builder sends the whole template). */
	void setContent(String name, String language, String footer, List<TemplateBlock> blocks, long by) {
		this.name = name;
		this.language = language;
		this.footer = footer;
		this.blocks.clear();
		this.blocks.addAll(blocks);
		this.updatedBy = by;
		this.updatedAt = Instant.now();
	}

	void setActive(boolean active) {
		this.active = active;
	}

	public Long getId() { return id; }
	public String getName() { return name; }
	public String getLanguage() { return language; }
	public boolean isActive() { return active; }
	public String getFooter() { return footer; }
	public List<TemplateBlock> getBlocks() { return blocks; }
	public Long getCreatedBy() { return createdBy; }
	public Long getUpdatedBy() { return updatedBy; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
}
