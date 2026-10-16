package com.onebase.template;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A photo, PDF or audio of a template (table {@code message_template_files}). Kept in
 * the database, so it is backed up with it. The type is the one read
 * from the bytes on upload ({@link TemplateRules#sniff}), never the one the browser said.
 *
 * <p>Uploaded before the template is saved; one that no template uses is deleted after
 * the next save ({@link TemplateFileCleanup}).
 */
@Entity
@Table(name = "message_template_files")
public class TemplateFile {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@JdbcTypeCode(SqlTypes.VARBINARY)
	@Column(nullable = false)
	private byte[] data;

	@Column(nullable = false)
	private String mime;

	@Column(nullable = false)
	private String filename;

	@Column(name = "size_bytes", nullable = false)
	private int sizeBytes;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	protected TemplateFile() {
	}

	TemplateFile(byte[] data, String mime, String filename) {
		this.data = data;
		this.mime = mime;
		this.filename = filename;
		this.sizeBytes = data.length;
	}

	public Long getId() { return id; }
	public byte[] getData() { return data; }
	public String getMime() { return mime; }
	public String getFilename() { return filename; }
	public int getSizeBytes() { return sizeBytes; }
	public Instant getCreatedAt() { return createdAt; }
}
