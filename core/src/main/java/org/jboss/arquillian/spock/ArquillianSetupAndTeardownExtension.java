package org.jboss.arquillian.spock;

import org.spockframework.runtime.extension.IGlobalExtension;

public class ArquillianSetupAndTeardownExtension implements IGlobalExtension {
	private ArquillianTestContext context;

	public ArquillianSetupAndTeardownExtension(ArquillianTestContext context) {
		this.context = context;
	}

	@Override
	public void stop() {
		context.close();
	}
}
