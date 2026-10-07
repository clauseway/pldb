// ABOUTME: The table constraint: a posted relation lookup held as a narrowing constraint
// ABOUTME: over candidate rows instead of an enumeration, with labelling as the branch point.

/**
 * A posted relation lookup as a constraint over its candidate rows.
 * {@link org.clauseway.pldb.constraints.TableConstraints} is the lattice factor and the
 * entry point: {@link org.clauseway.pldb.constraints.TableConstraints#posted} posts a lookup
 * over an {@code AnswerSource} or an {@code AnswerProducer},
 * {@link org.clauseway.pldb.constraints.TableConstraints#postedRule} posts a rule, and
 * {@link org.clauseway.pldb.constraints.TableConstraints#labelo} enumerates a variable's live
 * support. {@link org.clauseway.pldb.constraints.Support} is the finite domain a free column
 * narrows to, and {@link org.clauseway.pldb.constraints.TableParkingPropagator} is the parking
 * propagator for the asynchronous kind. The relations package reaches this package through
 * {@code Literal#posted()}; the sources it reads come from the root package's contracts.
 */
package org.clauseway.pldb.constraints;
