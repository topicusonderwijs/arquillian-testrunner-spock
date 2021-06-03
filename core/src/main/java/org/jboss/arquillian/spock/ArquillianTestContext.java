package org.jboss.arquillian.spock;

import java.io.Closeable;

import org.jboss.arquillian.test.spi.TestRunnerAdaptor;
import org.jboss.arquillian.test.spi.TestRunnerAdaptorBuilder;

public class ArquillianTestContext implements Closeable {
	private TestRunnerAdaptor adaptor;

	private Throwable caughtInitializationException;

	private static ThreadLocal<Boolean> inArquillian = ThreadLocal.withInitial(() -> false);

	public TestRunnerAdaptor getAdaptor() throws Exception {
		if (adaptor == null) {
			initializeAdaptor();
		}

		// no, initialization has been attempted before and failed, refuse
		// to do anything else
		if (hasInitializationException())
			handleSuiteLevelFailure();
		return adaptor;
	}

	private void initializeAdaptor() throws Exception {
		try {
			// ARQ-1742 If exceptions happen during boot
			adaptor = TestRunnerAdaptorBuilder.build();
			// don't set it if beforeSuite fails
			adaptor.beforeSuite();
		} catch (Exception e) {
			// caught exception during BeforeSuite, mark this as failed
			handleBeforeSuiteFailure(e);
		}
	}

	@Override
	public void close() {
		try {
			if (adaptor != null) {
				adaptor.afterSuite();
				adaptor.shutdown();
			}
		} catch (Exception e) {
			throw new RuntimeException("Could not run @AfterSuite", e);
		}
	}

	private void handleBeforeSuiteFailure(Exception e) throws Exception {
		caughtInitializationException = e;
		throw e;
	}

	private void handleSuiteLevelFailure() {
		throw new RuntimeException(
				"Arquillian initialization has already been attempted, but failed. See previous exceptions for cause",
				caughtInitializationException);
	}

	private boolean hasInitializationException() {
		return caughtInitializationException != null;
	}

	public static void setInArquillian(boolean value) {
		inArquillian.set(value);
	}

	public static boolean isInArquillian() {
		return inArquillian.get();
	}

	public static void clearInArquillian() {
		inArquillian.remove();
	}
}
