package org.jboss.arquillian.spock;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

public class ArquillianSetupAndTeardownSessionListener implements LauncherSessionListener {
	private static ArquillianTestContext context;

	public static ArquillianTestContext getContext() {
		if (context == null) {
			context = new ArquillianTestContext();
		}
		return context;
	}
	
	@Override
	public void launcherSessionClosed(LauncherSession session) {
		if (context != null)
			context.close();
	}
}
