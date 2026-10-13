package org.clauseway.pldb.inmemory;

import java.util.HashSet;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.clauseway.logic.unification.terms.Reified;
import org.clauseway.logic.unification.terms.Term;
import org.clauseway.vavr.collection.LinkedHashMap;
import org.clauseway.vavr.collection.LinkedHashSet;
import org.clauseway.vavr.collection.Map;
import org.clauseway.vavr.collection.Set;

@Value
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
class ColumnIndex {

	Map<Object, Set<Reified<?>>> buckets;
	Set<Reified<?>> wide;

	static ColumnIndex empty() {
		return new ColumnIndex(LinkedHashMap.empty(), LinkedHashSet.empty());
	}

	ColumnIndex with(Term<Object> cell, Reified<?> image) {
		if (isFree(cell)) {
			return new ColumnIndex(buckets, wide.add(image));
		}
		Object key = getKey(cell);
		Set<Reified<?>> bucket = buckets.getOrElse(key, LinkedHashSet.empty());
		return new ColumnIndex(buckets.put(key, bucket.add(image)), wide);
	}

	ColumnIndex without(Term<Object> cell, Reified<?> image) {
		if (isFree(cell)) {
			return new ColumnIndex(buckets, wide.remove(image));
		}
		Object key = getKey(cell);
		return new ColumnIndex(buckets.get(key)
				.map(bucket -> buckets.put(key, bucket.remove(image)))
				.getOrElse(buckets), wide);
	}

	/** The value's bucket plus every row free at this column, as mutable scratch. */
	HashSet<Reified<?>> matching(Object value) {
		HashSet<Reified<?>> matched = new HashSet<>();
		buckets.get(value).forEach(bucket -> bucket.forEach(matched::add));
		wide.forEach(matched::add);
		return matched;
	}

	private static boolean isFree(Term<?> v) {
		return !v.isVal();
	}

	private static Object getKey(Term<Object> cell) {
		return cell.get();
	}
}
