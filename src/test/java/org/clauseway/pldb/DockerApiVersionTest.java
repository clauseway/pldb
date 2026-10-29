package org.clauseway.pldb;

// ABOUTME: The Docker client config names an API version: left unnamed, Testcontainers pins 1.32, which a
// ABOUTME: current Docker daemon refuses, and the Postgres receipts cannot start a container.

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;
import org.testcontainers.shaded.com.github.dockerjava.core.DefaultDockerClientConfig;
import org.testcontainers.shaded.com.github.dockerjava.core.RemoteApiVersion;

public class DockerApiVersionTest {

	@Test
	public void theClientConfigNamesAnApiVersionTheDaemonAccepts() {
		RemoteApiVersion version = DefaultDockerClientConfig.createDefaultConfigBuilder().build().getApiVersion();
		assertThat(version).isNotEqualTo(RemoteApiVersion.UNKNOWN_VERSION);
		assertThat(version.isGreaterOrEqual(RemoteApiVersion.VERSION_1_44)).isTrue();
	}
}
