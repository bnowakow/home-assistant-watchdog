// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.persistence

import org.postgresql.util.PGobject
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Configuration
class JsonbJdbcConfiguration(
	private val objectMapper: ObjectMapper,
) : AbstractJdbcConfiguration() {
	override fun userConverters(): MutableList<*> =
		mutableListOf(
			JsonNodeToPgObjectConverter(objectMapper),
			PgObjectToJsonNodeConverter(objectMapper),
			StringToJsonNodeConverter(objectMapper),
		)
}

@WritingConverter
private class JsonNodeToPgObjectConverter(
	private val objectMapper: ObjectMapper,
) : Converter<JsonNode, PGobject> {
	override fun convert(source: JsonNode): PGobject =
		PGobject().also {
			it.type = "jsonb"
			it.value = objectMapper.writeValueAsString(source)
		}
}

@ReadingConverter
private class PgObjectToJsonNodeConverter(
	private val objectMapper: ObjectMapper,
) : Converter<PGobject, JsonNode> {
	override fun convert(source: PGobject): JsonNode =
		objectMapper.readTree(source.value ?: "{}")
}

@ReadingConverter
private class StringToJsonNodeConverter(
	private val objectMapper: ObjectMapper,
) : Converter<String, JsonNode> {
	override fun convert(source: String): JsonNode =
		objectMapper.readTree(source)
}
