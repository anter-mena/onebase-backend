package com.onebase.template;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * One piece of a template's message (table {@code message_template_blocks}): a text,
 * a file (photo, PDF or audio), or a button under the block before it. Its place in
 * the message is the list index ({@code position}).
 */
@Embeddable
public class TemplateBlock {

	public enum Type { TEXT, IMAGE, DOCUMENT, AUDIO, BUTTON }

	public enum ButtonType { QUICK_REPLY, URL, PHONE }

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Type type;

	@Column(columnDefinition = "text")
	private String text;

	@Column(name = "file_id")
	private Long fileId;

	@Enumerated(EnumType.STRING)
	@Column(name = "button_type")
	private ButtonType buttonType;

	@Column(name = "button_label")
	private String buttonLabel;

	@Column(name = "button_value")
	private String buttonValue;

	protected TemplateBlock() {
	}

	private TemplateBlock(Type type, String text, Long fileId, ButtonType buttonType, String buttonLabel, String buttonValue) {
		this.type = type;
		this.text = text;
		this.fileId = fileId;
		this.buttonType = buttonType;
		this.buttonLabel = buttonLabel;
		this.buttonValue = buttonValue;
	}

	public static TemplateBlock text(String text) {
		return new TemplateBlock(Type.TEXT, text, null, null, null, null);
	}

	/** {@code type}: IMAGE, DOCUMENT or AUDIO. */
	public static TemplateBlock file(Type type, long fileId) {
		return new TemplateBlock(type, null, fileId, null, null, null);
	}

	/** {@code value}: the link (URL), the number (PHONE), or null (QUICK_REPLY). */
	public static TemplateBlock button(ButtonType buttonType, String label, String value) {
		return new TemplateBlock(Type.BUTTON, null, null, buttonType, label, value);
	}

	/** A photo, PDF or audio. */
	public boolean isFile() {
		return type == Type.IMAGE || type == Type.DOCUMENT || type == Type.AUDIO;
	}

	public Type getType() { return type; }
	public String getText() { return text; }
	public Long getFileId() { return fileId; }
	public ButtonType getButtonType() { return buttonType; }
	public String getButtonLabel() { return buttonLabel; }
	public String getButtonValue() { return buttonValue; }
}
