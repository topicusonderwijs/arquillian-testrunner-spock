package org.jboss.arquillian.spock.container;

import org.jboss.arquillian.container.spi.client.deployment.Deployment;
import org.jboss.arquillian.container.test.impl.RunModeUtils;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.arquillian.spock.extension.RunModeEvent;

public class RunModeEventHandler {

	@Inject
	private Instance<Deployment> deployment;

	public void handleEvent(@Observes RunModeEvent event) {
		boolean runAsClient = RunModeUtils.isRunAsClient(deployment.get(), event.getTestClass(), event.getTestMethod());
		event.setRunAsClient(runAsClient);
	}
}