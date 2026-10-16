package com.onebase.whatsapp;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Our own copy of every WhatsApp file (table {@code whatsapp_media}). Meta deletes its
 * copies after a while; these stay, and are backed up with the database.
 *
 * <p>What we send is kept as we send it ({@link #keep}); what arrives is fetched from
 * Meta shortly after by {@link WhatsAppMediaSweeper}, or at the latest when someone
 * opens it ({@link #fetch}).
 */
@Component
class WhatsAppMediaStore {

	private static final Logger log = LoggerFactory.getLogger(WhatsAppMediaStore.class);

	private final WhatsAppMediaRepository media;
	private final WhatsAppCloudApi api;

	WhatsAppMediaStore(WhatsAppMediaRepository media, WhatsAppCloudApi api) {
		this.media = media;
		this.api = api;
	}

	Optional<WhatsAppCloudApi.MediaFile> find(long messageId) {
		return media.findById(messageId).map(f -> new WhatsAppCloudApi.MediaFile(f.getData(), f.getMime()));
	}

	/** Keeps a copy, unless the message already has one (the sweeper and a click can meet). */
	void keep(long messageId, byte[] bytes, String mime) {
		if (bytes == null || bytes.length == 0 || media.existsById(messageId)) return;
		try {
			media.saveAndFlush(new WhatsAppMedia(messageId, bytes, mime));
		} catch (DataIntegrityViolationException e) {
			log.debug("Message id={} already has its file", messageId);
		}
	}

	/**
	 * Downloads a received file from Meta and keeps it. Throws what the download throws:
	 * {@link WhatsAppCloudApi.MediaGoneException} when Meta no longer has it.
	 */
	WhatsAppCloudApi.MediaFile fetch(WhatsAppMessage message) {
		WhatsAppCloudApi.MediaFile file = api.download(message.getMediaId());
		if (file == null || file.bytes() == null) throw new IllegalStateException("WhatsApp gave no file");
		String mime = file.mime() != null && !file.mime().isBlank() ? file.mime() : message.getMediaMime();
		keep(message.getId(), file.bytes(), mime);
		return new WhatsAppCloudApi.MediaFile(file.bytes(), mime == null ? "application/octet-stream" : mime);
	}
}
