package org.clauseway.pldb;

// ABOUTME: The test logging configuration keeps the Docker client's wire and header logging off: at DEBUG,
// ABOUTME: docker-java prints every request header, registry credentials included, into the build log.

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;
import org.slf4j.LoggerFactory;

public class TestLoggingTest {

	@Test
	public void theDockerClientDoesNotLogAtDebug() {
		assertThat(LoggerFactory.getLogger("com.github.dockerjava").isDebugEnabled()).isFalse();
		assertThat(LoggerFactory.getLogger("org.testcontainers").isDebugEnabled()).isFalse();
	}

	@Test
	public void theRootLoggerIsNotAtDebug() {
		assertThat(LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).isDebugEnabled()).isFalse();
	}
}
