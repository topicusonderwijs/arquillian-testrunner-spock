package org.jboss.arquillian.spock;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.jboss.arquillian.test.spi.TestResult;
import org.jboss.arquillian.test.spi.TestResult.Status;

/**
 * Carries the outcome of every iteration of a parameterized feature that ran in
 * the container back to the client, so the client can report each iteration
 * on its own. It travels as the throwable of the feature's {@link TestResult},
 * because that is the only part of the result the protocol transports.
 */
public class IterationResults extends Exception {
	private static final long serialVersionUID = 1L;

	public static class Iteration implements Serializable {
		private static final long serialVersionUID = 1L;

		private final int index;

		private final String displayName;

		private final TestResult result;

		public Iteration(int index, String displayName, TestResult result) {
			this.index = index;
			this.displayName = displayName;
			this.result = result;
		}

		public int getIndex() {
			return index;
		}

		public String getDisplayName() {
			return displayName;
		}

		public TestResult getResult() {
			return result;
		}
	}

	private final List<Iteration> iterations;

	private final TestResult featureResult;

	public IterationResults(List<Iteration> iterations, TestResult featureResult) {
		super(countFailures(iterations) + " of " + iterations.size() + " iterations failed");
		this.iterations = new ArrayList<>(iterations);
		this.iterations.sort(Comparator.comparingInt(Iteration::getIndex));
		this.featureResult = featureResult;
	}

	/**
	 * The iterations in the order the container ran them.
	 */
	public List<Iteration> getIterations() {
		return Collections.unmodifiableList(iterations);
	}

	/**
	 * The result of the feature outside its iterations, such as a failing data
	 * provider.
	 */
	public TestResult getFeatureResult() {
		return featureResult;
	}

	public boolean isFailed() {
		return isFailed(featureResult) || countFailures(iterations) > 0;
	}

	/**
	 * Combines all failures into one throwable, for a client that reports the
	 * feature as a whole.
	 */
	public Throwable toFailure() {
		Throwable first = null;
		List<TestResult> results = new ArrayList<>();
		results.add(featureResult);
		iterations.forEach(i -> results.add(i.getResult()));
		for (TestResult result : results) {
			if (!isFailed(result))
				continue;
			if (first == null)
				first = result.getThrowable();
			else
				first.addSuppressed(result.getThrowable());
		}
		return first;
	}

	private static long countFailures(List<Iteration> iterations) {
		return iterations.stream().filter(i -> isFailed(i.getResult())).count();
	}

	private static boolean isFailed(TestResult result) {
		return result != null && result.getStatus() == Status.FAILED && result.getThrowable() != null;
	}
}
