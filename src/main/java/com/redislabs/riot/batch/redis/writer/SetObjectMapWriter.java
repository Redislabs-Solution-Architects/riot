package com.redislabs.riot.batch.redis.writer;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectWriter;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SetObjectMapWriter extends SetMapWriter {

	private ObjectWriter objectWriter;

	public void setObjectWriter(ObjectWriter objectWriter) {
		this.objectWriter = objectWriter;
	}

	@Override
	protected String value(Map<String, Object> item) {
		try {
			return objectWriter.writeValueAsString(item);
		} catch (JsonProcessingException e) {
			log.error("Could not serialize value: {}", item, e);
			return null;
		}
	}

}