package org.clauseway.pldb;

// ABOUTME: The Docker gate in front of the Postgres receipts: without Docker they are skipped on a
// ABOUTME: developer's machine and FAIL on CI, where a skip would pass green with the receipts unrun.

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.AssumptionViolatedException;
import org.junit.Test;

public class DockerGateTest {

	@Test
	public void withDockerTheGateOpens() {
		DockerGate.require("the receipts need it", false, true);
		DockerGate.require("the receipts need it", true, true);
	}

	@Test
	public void withoutDockerOffCiTheTestIsSkipped() {
		assertThatThrownBy(() -> DockerGate.require("the receipts need it", false, false))
				.isInstanceOf(AssumptionViolatedException.class)
				.hasMessageContaining("Docker unavailable")
				.hasMessageContaining("the receipts need it");
	}

	@Test
	public void withoutDockerOnCiTheTestFails() {
		assertThatThrownBy(() -> DockerGate.require("the receipts need it", true, false))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("CI")
				.hasMessageContaining("Docker unavailable")
				.hasMessageContaining("the receipts need it");
	}
}
