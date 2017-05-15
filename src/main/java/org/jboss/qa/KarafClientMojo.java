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
package org.jboss.qa;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.agent.local.AgentImpl;
import org.apache.sshd.agent.local.LocalAgentFactory;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.keyboard.UserInteraction;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.RuntimeSshException;

import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.Ansi.Color;
import org.fusesource.jansi.AnsiConsole;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.net.URL;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Karaf client.
 * @author Martin Basovník
 */
@Mojo(name = "client")
public class KarafClientMojo extends AbstractMojo {
	/**
	 * Server port.
	 */
	@Parameter(property = "client.port", defaultValue = "8101")
	private int port;

	/**
	 * Server host.
	 */
	@Parameter(property = "client.host", defaultValue = "localhost")
	private String host;

	/**
	 * User name.
	 */
	@Parameter(property = "client.user", defaultValue = "karaf")
	private String user;

	/**
	 * User password.
	 */
	@Parameter(property = "client.password", defaultValue = "karaf")
	private String password;

	/**
	 * Retry connection establishment (up to attempts times).
	 */
	@Parameter(property = "client.attempts", defaultValue = "0")
	private int attempts;

	/**
	 * Intra-retry delay.
	 */
	@Parameter(property = "client.delay", defaultValue = "2")
	private int delay;

	/**
	 * List with OSGi commands.
	 */
	@Parameter(property = "client.commands")
	private List<String> commands;

	/**
	 * Scripts with OSGi commands.
	 */
	@Parameter(property = "client.scripts")
	private List<File> scripts;

	/**
	 * Key file location when using key login.
	 */
	@Parameter(property = "client.keyFile")
	private File keyFile;

	/**
	 * Skip execution.
	 *
	 * @since 1.1
	 */
	@Parameter(property = "client.skip", defaultValue = "false")
	private boolean skip;

	private static final String NEW_LINE = System.getProperty("line.separator");

	@Override
	public void execute() throws MojoExecutionException {
		if (skip == true) {
			getLog().info("Execution is skipped");
			return;
		}
		// Add commands from scripts to already declared commands
		for (File script : scripts) {
			try (BufferedReader br = new BufferedReader(new FileReader(script))) {
				String line;
				while ((line = br.readLine()) != null) {
					line = line.trim();
					if (line.isEmpty()) {
						continue;
					}
					commands.add(line);
				}
			} catch (Exception e) {
				throw new MojoExecutionException(e, e.getMessage(), e.toString());
			}
		}

		if (commands.isEmpty()) {
			getLog().warn("No OSGi command was specified");
			return;
		}

		final StringWriter sw = new StringWriter();
		final PrintWriter pw = new PrintWriter(sw, true);
		for (String cmd : commands) {
			getLog().info(cmd);
			pw.println(cmd);
		}
		execute(sw.toString());
	}

	/**
	 * Executes karaf command.
	 *
	 * @param cmd a karaf command to execute
	 * @throws MojoExecutionException if plugin execution fails
	 */
	protected void execute(String cmd) throws MojoExecutionException {
		SshClient client = null;
		try {
			final Console console = System.console();
			client = SshClient.setUpDefaultClient();
			setupAgent(user, keyFile, client);

			client.setUserInteraction(new UserInteraction() {
				@Override
				public boolean isInteractionAllowed(ClientSession clientSession) {
					return true;
				}

				@Override
				public void serverVersionInfo(ClientSession clientSession, List<String> list) {

				}

				@Override
				public String getUpdatedPassword(ClientSession session, String prompt, String lang) {
					return password;
				}

				public void welcome(ClientSession session, String banner, String lang) {
					console.printf(banner);
				}

				public String[] interactive(ClientSession session, String name, String instruction, String lang, String[] prompt, boolean[] echo) {
					String[] answers = new String[prompt.length];
					try {
						for (int i = 0; i < prompt.length; i++) {
							if (console != null) {
								if (echo[i]) {
									answers[i] = console.readLine(prompt[i] + " ");
								} else {
									answers[i] = new String(console.readPassword(prompt[i] + " "));
								}
							}
						}
					} catch (IOError e) {
					}
					return answers;
				}
			});
			client.start();
			if (console != null) {
				console.printf("Logging in as %s\n", user);
			}
			final ClientSession session = connect(client);
			if (password != null) {
				session.addPasswordIdentity(password);
			}
			session.auth().verify();

			ClientChannel channel;
			channel = session.createChannel("exec", cmd.concat(NEW_LINE));
			channel.setIn(new ByteArrayInputStream(new byte[0]));
			final ByteArrayOutputStream sout = new ByteArrayOutputStream();
			final ByteArrayOutputStream serr = new ByteArrayOutputStream();
			channel.setOut(AnsiConsole.wrapOutputStream(sout));
			channel.setErr(AnsiConsole.wrapOutputStream(serr));
			channel.open();
			channel.waitFor(Arrays.asList(ClientChannelEvent.CLOSED), 0);

			sout.writeTo(System.out);
			serr.writeTo(System.err);

			// Expects issue KARAF-2623 is fixed
			final boolean isError = (channel.getExitStatus() != null && channel.getExitStatus().intValue() != 0);
			if (isError) {
				final String errorMarker = Ansi.ansi().fg(Color.RED).toString();
				final int fromIndex = sout.toString().indexOf(errorMarker) + errorMarker.length();
				final int toIndex = sout.toString().lastIndexOf(Ansi.ansi().fg(Color.DEFAULT).toString());
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

	private void setupAgent(String user, File keyFile, SshClient client) {
		final URL builtInPrivateKey = KarafClientMojo.class.getClassLoader().getResource("karaf.key");
		final SshAgent agent = startAgent(user, builtInPrivateKey, keyFile);
		client.setAgentFactory(new LocalAgentFactory(agent));
		client.getProperties().put(SshAgent.SSH_AUTHSOCKET_ENV_NAME, "local");
	}

	private SshAgent startAgent(String user, URL privateKeyUrl, File keyFile) {
		try (InputStream is = privateKeyUrl.openStream()) {
			final SshAgent agent = new AgentImpl();
			final ObjectInputStream r = new ObjectInputStream(is);
			final KeyPair keyPair = (KeyPair) r.readObject();
			is.close();
			agent.addIdentity(keyPair, user);
			if (keyFile != null) {
				KeyPair kp = null;
				try (Reader reader = new InputStreamReader(new FileInputStream(keyFile))) {

					try (PEMParser parser = new PEMParser(reader)) {
						Object o = parser.readObject();

						JcaPEMKeyConverter pemConverter = new JcaPEMKeyConverter();
						pemConverter.setProvider("BC");

						if (o instanceof PEMKeyPair) {
							o = pemConverter.getKeyPair((PEMKeyPair) o);
							kp = (KeyPair) o;
						} else if (o instanceof KeyPair) {
							kp = (KeyPair) o;
						}
					}
				} catch (Exception e) {
					getLog().warn("Unable to read key " + keyFile.getName(), e);
				}
				agent.addIdentity(kp, user);
			}
			return agent;
		} catch (Throwable e) {
			getLog().error("Error starting ssh agent for: " + e.getMessage(), e);
			return null;
		}
	}

	private ClientSession connect(SshClient client) throws IOException, InterruptedException {
		int retries = 0;
		ClientSession session = null;
		do {
			final ConnectFuture future = client.connect(user, host, port);
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
		return session;
	}
}
