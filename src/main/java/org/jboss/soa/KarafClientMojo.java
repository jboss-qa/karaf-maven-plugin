/*
 * Copyright 2015 Red Hat Inc. and/or its affiliates and other contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.soa;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.sshd.ClientChannel;
import org.apache.sshd.ClientSession;
import org.apache.sshd.SshClient;
import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.agent.local.AgentImpl;
import org.apache.sshd.agent.local.LocalAgentFactory;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.common.RuntimeSshException;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;

import org.fusesource.jansi.AnsiConsole;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.net.URL;
import java.security.KeyPair;
import java.util.concurrent.TimeUnit;

/**
 * Execute OSGi goals on karaf.
 * @author Martin Basovník
 */
@Mojo(name = "execute")
public class KarafClientMojo extends AbstractMojo {

	/**
	 * Server port.
	 */
	@Parameter(property = "execute.port", defaultValue = "8101")
	private int port;

	/**
	 * Server host.
	 */
	@Parameter(property = "execute.host", defaultValue = "localhost")
	private String host;

	/**
	 * User name.
	 */
	@Parameter(property = "execute.user", defaultValue = "karaf")
	private String user;

	/**
	 * User password.
	 */
	@Parameter(property = "execute.password", defaultValue = "karaf")
	private String password;

	/**
	 * Retry connection establishment (up to attempts times).
	 */
	@Parameter(property = "execute.attempts", defaultValue = "0")
	private int attempts;

	/**
	 * Intra-retry delay.
	 */
	@Parameter(property = "execute.attempts", defaultValue = "2")
	private int delay;

	private static final String NEW_LINE = System.getProperty("line.separator");

	private static final String ERR_MSG = "Error executing command";

	/**
	 * List with OSGi commands.
	 */
	@Parameter(property = "execute.commands", required = true)
	private String[] commands;

	public void execute() throws MojoExecutionException {
		final StringBuilder sb = new StringBuilder();
		for (String cmd : commands) {
			sb.append(cmd).append(NEW_LINE);
		}
		execute(sb.toString());
	}

	protected void execute(String cmd) throws MojoExecutionException {
		SshClient client = null;
		SshAgent agent;
		try {
			agent = startAgent(user, null); // keyFile is not supported
			client = SshClient.setUpDefaultClient();
			client.setAgentFactory(new LocalAgentFactory(agent));
			client.getProperties().put(SshAgent.SSH_AUTHSOCKET_ENV_NAME, "local");
			client.start();
			int retries = 0;
			ClientSession session = null;
			do {
				final ConnectFuture future = client.connect(host, port);
				future.await();
				try {
					session = future.getSession();
				} catch (RuntimeSshException ex) {
					if (retries++ < attempts) {
						Thread.sleep(TimeUnit.SECONDS.toMillis(delay));
						getLog().info("retrying (attempt " + retries + ") ...");
					} else {
						throw ex;
					}
				}
			} while (session == null);
			if (!session.authAgent(user).await().isSuccess()) {
				if (password == null) {
					final Console console = System.console();
					if (console != null) {
						final char[] readPassword = console.readPassword("Password: ");
						if (readPassword != null) {
							password = new String(readPassword);
						}
					} else {
						throw new Exception("Unable to prompt password: could not get system console");
					}
				}
				if (!session.authPassword(user, password).await().isSuccess()) {
					throw new Exception("Authentication failure");
				}
			}
			ClientChannel channel;
			channel = session.createChannel("exec", cmd.concat(NEW_LINE));
			channel.setIn(new ByteArrayInputStream(new byte[0]));
			final ByteArrayOutputStream sout = new ByteArrayOutputStream();
			final ByteArrayOutputStream serr = new ByteArrayOutputStream();
			channel.setOut(AnsiConsole.wrapOutputStream(sout));
			channel.setErr(AnsiConsole.wrapOutputStream(serr));
			channel.open();
			channel.waitFor(ClientChannel.CLOSED, 0);

			sout.writeTo(System.out);
			serr.writeTo(System.err);

			boolean isError = (channel.getExitStatus() != null && channel.getExitStatus().intValue() != 0);
			// BUG: https://issues.apache.org/jira/browse/KARAF-2623
			// https://github.com/apache/karaf/blob/karaf-2.3.6/shell/console/src/
			// 		main/java/org/apache/felix/gogo/commands/CommandException.java
			isError |= sout.toString().contains(ERR_MSG); // TODO(mbasovni): Delete when possible
			if (isError) {
				final int fromIndex = sout.toString().indexOf(ERR_MSG);
				final int toIndex = sout.toString().lastIndexOf(NEW_LINE);
				throw new MojoExecutionException(NEW_LINE + sout.toString().substring(fromIndex, toIndex));
			}
		} catch (MojoExecutionException e) {
			throw e;
		} catch (Throwable t) {
			throw new MojoExecutionException(t, t.getMessage(), t.toString());
		} finally {
			try {
				client.stop();
			} catch (Throwable t) {
				throw new MojoExecutionException(t, t.getMessage(), t.toString());
			}
		}
	}

	protected SshAgent startAgent(String user, String keyFile) {
		try {
			final SshAgent local = new AgentImpl();
			final URL builtInPrivateKey = KarafClientMojo.class.getClassLoader().getResource("karaf.key");
			final InputStream is = builtInPrivateKey.openStream();
			final ObjectInputStream r = new ObjectInputStream(is);
			final KeyPair keyPair = (KeyPair) r.readObject();
			is.close();
			local.addIdentity(keyPair, user);
			if (keyFile != null) {
				final String[] keyFiles = new String[] {keyFile};
				final FileKeyPairProvider fileKeyPairProvider = new FileKeyPairProvider(keyFiles);
				for (KeyPair key : fileKeyPairProvider.loadKeys()) {
					local.addIdentity(key, user);
				}
			}
			return local;
		} catch (Throwable e) {
			getLog().error("Error starting ssh agent for: " + e.getMessage());
			return null;
		}
	}
}
