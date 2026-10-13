package org.clauseway.pldb.relations;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@EqualsAndHashCode
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Property<T> {
	private final String name;
	private final boolean indexed;
	private final boolean ground;

	public static <T> Property<T> of(String name) {
		return new Property<>(name, false, false);
	}

	public Property<T> indexed() {
		return new Property<T>(name, true, ground);
	}

	public Property<T> ground() {
		return new Property<T>(name, indexed, true);
	}

	@Override
	public String toString() {
		String access = (indexed ? "i" : "") + (ground ? "g" : "");
		return name + (access.isEmpty() ? "" : "_" + access);
	}
}
