package org.jboss.arquillian.spock;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

public class ArquillianSetupAndTeardownSessionListener implements LauncherSessionListener {
	private ArquillianTestContext context;

	@Override
	public void launcherSessionOpened(LauncherSession session) {
		context = new ArquillianTestContext();
	}
	
	@Override
	public void launcherSessionClosed(LauncherSession session) {
		context.close();
	}
}
