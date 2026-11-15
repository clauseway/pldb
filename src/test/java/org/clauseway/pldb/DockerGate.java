package org.clauseway.pldb;

// ABOUTME: The gate in front of a receipt that needs Docker: skipped where Docker is absent on a developer's
// ABOUTME: machine, failed where it is absent on CI, so a green build never means the receipt went unrun.

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Assume;
import org.testcontainers.DockerClientFactory;

public final class DockerGate {

	private DockerGate() {
	}

	/** Lets the receipt run with Docker; without it, skips on a developer's machine and fails on CI, the reason saying what needs it. */
	public static void require(String reason) {
		require(reason, System.getenv("CI") != null, DockerClientFactory.instance().isDockerAvailable());
	}

	static void require(String reason, boolean ci, boolean available) {
		String message = "Docker unavailable — " + reason;
		if (ci) {
			assertThat(available).as("CI: " + message).isTrue();
		}
		Assume.assumeTrue(message, available);
	}
}
