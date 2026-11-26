package org.jboss.arquillian.spock;

import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.List;

import org.jboss.arquillian.spock.extension.RunModeEvent;
import org.jboss.arquillian.test.spi.LifecycleMethodExecutor;
import org.jboss.arquillian.test.spi.TestMethodExecutor;
import org.jboss.arquillian.test.spi.TestResult;
import org.jboss.arquillian.test.spi.TestResult.Status;
import org.opentest4j.TestAbortedException;
import org.spockframework.runtime.AbstractRunListener;
import org.spockframework.runtime.extension.IAnnotationDrivenExtension;
import org.spockframework.runtime.extension.IDataDriver;
import org.spockframework.runtime.extension.IMethodInterceptor;
import org.spockframework.runtime.extension.IMethodInvocation;
import org.spockframework.runtime.model.ErrorInfo;
import org.spockframework.runtime.model.FeatureInfo;
import org.spockframework.runtime.model.IterationInfo;
import org.spockframework.runtime.model.MethodInfo;
import org.spockframework.runtime.model.NameProvider;
import org.spockframework.runtime.model.SpecInfo;

public class RunWithArquillianExtension implements IAnnotationDrivenExtension<RunWithArquillian> {
	private static class ErrorCollector extends AbstractRunListener {
		private Throwable first = null;
		private List<Throwable> errors = new LinkedList<>();

		@Override
		public void error(ErrorInfo error) {
			if (first == null)
				first = error.getException();
			else
				errors.add(error.getException());
		}

		public boolean isError() {
			return first != null;
		}

		public void clear() {
			first = null;
			errors.clear();
		}

		public Throwable getFirst() {
			return first;
		}

		public List<Throwable> getErrors() {
			return errors;
		}
	}

	private ArquillianTestContext testContext;

	public RunWithArquillianExtension() {
		this.testContext = ArquillianSetupAndTeardownSessionListener.getContext();
	}

	@Override
	public void visitSpecAnnotation(RunWithArquillian annotation, SpecInfo spec) {
		if (!spec.getIsBottomSpec()) {
			return;
		}

		ErrorCollector errors = new ErrorCollector();
		spec.addListener(errors);

		spec.addSetupSpecInterceptor(
				c -> testContext.getAdaptor().beforeClass(spec.getReflection(), runIfNotInArquillian(testContext, c)));
		spec.addSetupInterceptor(c -> testContext.getAdaptor().before(c.getInstance(),
				c.getFeature().getFeatureMethod().getReflection(), runIfInArquillianOrClient(testContext, c)));
		spec.addCleanupInterceptor(c -> testContext.getAdaptor().after(c.getInstance(),
				c.getFeature().getFeatureMethod().getReflection(), runIfInArquillianOrClient(testContext, c)));
		spec.addCleanupSpecInterceptor(
				c -> testContext.getAdaptor().afterClass(spec.getReflection(), runIfNotInArquillian(testContext, c)));
		spec.getAllFeatures().forEach(feature -> {
			feature.addInterceptor(c -> {
				if (ArquillianTestContext.isInArquillian() || isRunAsClient(testContext, c)) {
					c.proceed();
				} else {
					TestResult result = interceptTestInvocation(testContext, c, errors, true);
					Throwable throwable = result.getThrowable();
					if (throwable instanceof IterationResults) {
						reportIterations(c, (IterationResults) throwable);
					} else if (throwable != null) {
						throw throwable;
					}
				}
			});
		});
		spec.getAllFeatures().forEach(feature -> {
			feature.getFeatureMethod().addInterceptor(c -> {
				if (ArquillianTestContext.isInArquillian() || !isRunAsClient(testContext, c)) {
					c.proceed();
				} else {
					TestResult result = interceptTestInvocation(testContext, c, errors, false);
					if (result.getThrowable() != null)
						throw result.getThrowable();
				}
			});
		});
	}

	/**
	 * Lets Spock run the iterations the container executed once more on the
	 * client, without data providers and without executing anything, so every
	 * iteration is reported with its own name and outcome.
	 */
	private void reportIterations(IMethodInvocation c, IterationResults results) throws Throwable {
		FeatureInfo feature = c.getFeature();
		if (!feature.isReportIterations()) {
			Throwable failure = results.toFailure();
			if (failure != null)
				throw failure;
			return;
		}

		MethodInfo dataProcessor = feature.getDataProcessorMethod();
		boolean forceParameterized = feature.isForceParameterized();
		NameProvider<IterationInfo> nameProvider = feature.getIterationNameProvider();
		IDataDriver dataDriver = feature.getDataDriver();
		IMethodInterceptor replay = i -> replayIteration(results, i.getIteration().getIterationIndex());
		try {
			feature.setDataProcessorMethod(null);
			feature.setForceParameterized(true);
			feature.setIterationNameProvider(
					iteration -> results.getIterations().get(iteration.getIterationIndex()).getDisplayName());
			feature.setDataDriver((dataIterator, iterationRunner, parameters) -> {
				int count = results.getIterations().size();
				for (int i = 0; i < count; i++) {
					iterationRunner.runIteration(new Object[parameters.size()], count);
				}
			});
			// outermost, so setup, cleanup and the interceptors of other extensions stay in the container
			feature.getIterationInterceptors().add(0, replay);
			c.proceed();
		} finally {
			feature.getIterationInterceptors().remove(replay);
			feature.setDataProcessorMethod(dataProcessor);
			feature.setForceParameterized(forceParameterized);
			feature.setIterationNameProvider(nameProvider);
			feature.setDataDriver(dataDriver);
		}

		TestResult featureResult = results.getFeatureResult();
		if (featureResult != null && featureResult.getStatus() == Status.FAILED
				&& featureResult.getThrowable() != null) {
			throw featureResult.getThrowable();
		}
	}

	private void replayIteration(IterationResults results, int index) throws Throwable {
		TestResult result = results.getIterations().get(index).getResult();
		if (result.getStatus() == Status.SKIPPED) {
			throw new TestAbortedException(result.getDescription());
		}
		if (result.getStatus() == Status.FAILED && result.getThrowable() != null) {
			throw result.getThrowable();
		}
	}

	private LifecycleMethodExecutor runIfNotInArquillian(ArquillianTestContext testContext, IMethodInvocation c) {
		return () -> {
			if (!ArquillianTestContext.isInArquillian()) {
				c.proceed();
			}
		};
	}

	private LifecycleMethodExecutor runIfInArquillianOrClient(ArquillianTestContext testContext, IMethodInvocation c) {
		return () -> {
			if (ArquillianTestContext.isInArquillian() || isRunAsClient(testContext, c)) {
				c.proceed();
			}
		};
	}

	private TestResult interceptTestInvocation(ArquillianTestContext testContext, IMethodInvocation c,
			ErrorCollector errors, boolean triggerEvents) throws Throwable {
		try {
			if (triggerEvents) {
				testContext.getAdaptor().before(c.getInstance(), c.getFeature().getFeatureMethod().getReflection(),
						LifecycleMethodExecutor.NO_OP);
			}
			return testContext.getAdaptor().test(new TestMethodExecutor() {
				@Override
				public String getMethodName() {
					return c.getFeature().getFeatureMethod().getReflection().getName();
				}

				@Override
				public Method getMethod() {
					return c.getFeature().getFeatureMethod().getReflection();
				}

				@Override
				public Object getInstance() {
					return c.getInstance();
				}

				@Override
				public void invoke(Object... parameters) throws Throwable {
					errors.clear();
					c.proceed();
					if (errors.isError()) {
						Throwable first = errors.getFirst();
						errors.getErrors().forEach(first::addSuppressed);
						throw first;
					}
				}
			});
		} finally {
			if (triggerEvents) {
				testContext.getAdaptor().after(c.getInstance(), c.getFeature().getFeatureMethod().getReflection(),
						LifecycleMethodExecutor.NO_OP);
			}
		}
	}

	private boolean isRunAsClient(ArquillianTestContext testContext, IMethodInvocation invocation) throws Exception {
		RunModeEvent runModeEvent = new RunModeEvent(invocation.getInstance(),
				invocation.getFeature().getFeatureMethod().getReflection());
		testContext.getAdaptor().fireCustomLifecycle(runModeEvent);
		return runModeEvent.isRunAsClient();
	}
}
