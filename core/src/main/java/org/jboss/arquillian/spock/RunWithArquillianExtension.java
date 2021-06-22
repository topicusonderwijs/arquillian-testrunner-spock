package org.jboss.arquillian.spock;

import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.List;

import org.jboss.arquillian.spock.extension.RunModeEvent;
import org.jboss.arquillian.test.spi.LifecycleMethodExecutor;
import org.jboss.arquillian.test.spi.TestMethodExecutor;
import org.jboss.arquillian.test.spi.TestResult;
import org.spockframework.runtime.AbstractRunListener;
import org.spockframework.runtime.extension.IAnnotationDrivenExtension;
import org.spockframework.runtime.extension.IMethodInvocation;
import org.spockframework.runtime.model.ErrorInfo;
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

	@Override
	public void visitSpecAnnotation(RunWithArquillian annotation, SpecInfo spec) {
		if (!spec.getIsBottomSpec()) {
			return;
		}

		ArquillianTestContext testContext = new ArquillianTestContext();
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
		spec.addInterceptor(c -> {
			try {
				c.proceed();
			} finally {
				testContext.close();
			}
		});
		spec.getAllFeatures().forEach(feature -> {
			feature.addInterceptor(c -> {
				if (ArquillianTestContext.isInArquillian() || isRunAsClient(testContext, c)) {
					c.proceed();
				} else {
					TestResult result = interceptTestInvocation(testContext, c, errors);
					if (result.getThrowable() != null)
						throw result.getThrowable();
				}
			});
		});
		spec.getAllFeatures().forEach(feature -> {
			feature.getFeatureMethod().addInterceptor(c -> {
				if (ArquillianTestContext.isInArquillian() || !isRunAsClient(testContext, c)) {
					c.proceed();
				} else {
					TestResult result = interceptTestInvocation(testContext, c, errors);
					if (result.getThrowable() != null)
						throw result.getThrowable();
				}
			});
		});
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
			ErrorCollector errors) throws Throwable {
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
	}

	private boolean isRunAsClient(ArquillianTestContext testContext, IMethodInvocation invocation) throws Exception {
		RunModeEvent runModeEvent = new RunModeEvent(invocation.getInstance(),
				invocation.getFeature().getFeatureMethod().getReflection());
		testContext.getAdaptor().fireCustomLifecycle(runModeEvent);
		return runModeEvent.isRunAsClient();
	}
}
