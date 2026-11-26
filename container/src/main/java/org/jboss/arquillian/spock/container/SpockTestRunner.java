package org.jboss.arquillian.spock.container;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.arquillian.container.test.spi.TestRunner;
import org.jboss.arquillian.spock.ArquillianTestContext;
import org.jboss.arquillian.spock.IterationResults;
import org.jboss.arquillian.test.spi.TestResult;
import org.jboss.arquillian.test.spi.TestResult.Status;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.PostDiscoveryFilter;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.opentest4j.TestAbortedException;
import org.spockframework.runtime.FeatureNode;
import org.spockframework.runtime.model.FeatureInfo;

public class SpockTestRunner implements TestRunner {

	@Override
	public TestResult execute(Class<?> testClass, String methodName) {
		TestResult testResult;
		ArquillianTestMethodExecutionListener listener = new ArquillianTestMethodExecutionListener();
		ArquillianTestContext.setInArquillian(true);
		try {
			final AtomicInteger matchCounter = new AtomicInteger(0);
			Launcher launcher = LauncherFactory.create();
			launcher.registerTestExecutionListeners(listener);
			LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
					.selectors(DiscoverySelectors.selectClass(testClass)).filters((PostDiscoveryFilter) object -> {
						if (object instanceof FeatureNode) {
							FeatureInfo feature = ((FeatureNode) object).getNodeInfo();
							if (feature.getFeatureMethod().getReflection().getName().equals(methodName)) {
								matchCounter.incrementAndGet();
								return FilterResult.included("Matched method name");
							}
						}
						return FilterResult.excluded("Not matched");
					}).build();
			TestPlan plan = launcher.discover(request);

			if (matchCounter.get() > 1) {
				throw new JUnitException("Method name must be unique");
			}
			if (plan.containsTests()) {
				launcher.execute(request);
				testResult = listener.getTestResult();
			} else {
				throw new JUnitException("No test method found");
			}
		} catch (Throwable t) {
			testResult = TestResult.failed(t);
		} finally {
			ArquillianTestContext.clearInArquillian();
		}
		testResult.setEnd(System.currentTimeMillis());
		return testResult;
	}

	private static class ArquillianTestMethodExecutionListener implements TestExecutionListener {
		private static final String ITERATION_SEGMENT = "iteration";

		private TestResult result = TestResult.passed();

		private final List<IterationResults.Iteration> iterations = new ArrayList<>();

		public void executionSkipped(TestIdentifier testIdentifier, String reason) {
			if (isIteration(testIdentifier)) {
				addIteration(testIdentifier, TestResult.skipped(reason));
			} else {
				result = TestResult.skipped(reason);
			}
		}

		public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
			if (!testIdentifier.isTest()) {
				return;
			}
			TestResult converted = convert(testExecutionResult);
			if (isIteration(testIdentifier)) {
				addIteration(testIdentifier, converted);
			} else if (converted != null) {
				result = converted;
			}
		}

		private static TestResult convert(TestExecutionResult testExecutionResult) {
			switch (testExecutionResult.getStatus()) {
			case FAILED:
				return TestResult.failed(testExecutionResult.getThrowable().orElseGet(() -> new Exception("Failed")));
			case ABORTED:
				return TestResult.failed(
						testExecutionResult.getThrowable().orElseGet(() -> new TestAbortedException("Aborted")));
			default:
				return null;
			}
		}

		private static boolean isIteration(TestIdentifier testIdentifier) {
			return ITERATION_SEGMENT.equals(testIdentifier.getUniqueIdObject().getLastSegment().getType());
		}

		private void addIteration(TestIdentifier testIdentifier, TestResult iterationResult) {
			int index = Integer.parseInt(testIdentifier.getUniqueIdObject().getLastSegment().getValue());
			iterations.add(new IterationResults.Iteration(index, testIdentifier.getDisplayName(),
					iterationResult == null ? TestResult.passed() : iterationResult));
		}

		private TestResult getTestResult() {
			if (iterations.isEmpty()) {
				return result;
			}
			IterationResults iterationResults = new IterationResults(iterations, result);
			return new TestResult(iterationResults.isFailed() ? Status.FAILED : Status.PASSED, iterationResults);
		}
	}
}
