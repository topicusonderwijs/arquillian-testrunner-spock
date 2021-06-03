package org.jboss.arquillian.spock.container;

import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.arquillian.container.test.spi.TestRunner;
import org.jboss.arquillian.spock.ArquillianTestContext;
import org.jboss.arquillian.test.spi.TestResult;
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
						System.out.println(object+" selecting "+methodName);
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
		private TestResult result = TestResult.passed();

		public void executionSkipped(TestIdentifier testIdentifier, String reason) {
			result = TestResult.skipped(reason);
		}

		public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
			TestExecutionResult.Status status = testExecutionResult.getStatus();

			if (!testIdentifier.isTest()) {
				return;
			}
			switch (status) {
			case FAILED:
				result = TestResult.failed(testExecutionResult.getThrowable().orElseGet(() -> new Exception("Failed")));
				break;
			case ABORTED:
				result = TestResult.failed(
						testExecutionResult.getThrowable().orElseGet(() -> new TestAbortedException("Aborted")));
				break;
			case SUCCESSFUL:
				break;
			}
		}

		private TestResult getTestResult() {
			return result;
		}
	}
}