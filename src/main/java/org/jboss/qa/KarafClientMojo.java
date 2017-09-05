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

import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.AnsiConsole;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Karaf client.
 *
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
	 * Use strict host key checking.
	 */
	@Parameter(property = "client.strictHostKeyChecking", defaultValue = "yes")
	private String strictHostKeyChecking;

	/**
	 * Skip execution.
	 *
	 * @since 1.1
	 */
	@Parameter(property = "client.skip", defaultValue = "false")
	private boolean skip;

	private static final String NEW_LINE = System.getProperty("line.separator");
	private Session session = null;

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
		try {
			session = connect();
		} catch (Exception ex) {
			getLog().error("Can't connect to karaf host.", ex);
		}

		final StringWriter sw = new StringWriter();
		final PrintWriter pw = new PrintWriter(sw, true);
		for (String cmd : commands) {
			getLog().info(cmd);
			pw.println(cmd);
		}
		try {
			execute(sw.toString());
		} catch (Exception e) {
			getLog().warn(e);
		}

		session.disconnect();
	}

	/**
	 * Executes karaf command.
	 *
	 * @param cmd a karaf command to execute
	 * @throws MojoExecutionException if plugin execution fails
	 */
	protected void execute(String cmd) throws MojoExecutionException, JSchException, IOException {
		final Console console = System.console();

		ChannelExec channel = (ChannelExec) session.openChannel("exec");

		final ByteArrayOutputStream sout = new ByteArrayOutputStream();
		final ByteArrayOutputStream serr = new ByteArrayOutputStream();
		channel.setOutputStream(AnsiConsole.wrapOutputStream(sout));
		channel.setErrStream(AnsiConsole.wrapOutputStream(serr));

		channel.setCommand(cmd.concat(NEW_LINE));
		channel.connect();
		channel.start();

		while(!channel.isClosed()){
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				getLog().error(e);
			}
		}

		sout.writeTo(System.out);
		serr.writeTo(System.err);

		if (channel.getExitStatus() != 0) {
			final String errorMarker = Ansi.ansi().fg(Ansi.Color.RED).toString();
			final int start = sout.toString().indexOf(errorMarker);
			final int fromIndex = start == (-1) ? (-1) : start + errorMarker.length();
			final int toIndex = sout.toString().lastIndexOf(Ansi.ansi().fg(Ansi.Color.DEFAULT).toString());

			if(fromIndex != (-1) && toIndex != (-1) ){
				throw new MojoExecutionException(NEW_LINE +  sout.toString().substring(fromIndex, toIndex));
			}
			else{
				throw new MojoExecutionException(NEW_LINE + sout.toString());
			}
		}

		getLog().info("exit-status code: " + channel.getExitStatus());
		channel.disconnect();
	}

	private Session connect() throws InterruptedException, JSchException {
		int retries = 0;
		JSch jsch = new JSch();

		// set keyFile identity
		if (keyFile != null) {
			jsch.addIdentity(keyFile.getAbsolutePath());
		}

		java.util.Properties config = new java.util.Properties();
		config.put("StrictHostKeyChecking", strictHostKeyChecking);

		Session s = null;
		do {
			try {
				s = jsch.getSession(user, host, port);
				if (password != null) {
					s.setPassword(password);
				}

				s.setConfig(config);
				s.connect();
			} catch (JSchException ex) {
				if (retries++ < attempts) {
					Thread.sleep(TimeUnit.SECONDS.toMillis(delay));
					getLog().info("retrying (attempt " + retries + ") ...");
				} else {
					throw ex;
				}
			}
		} while (s == null || !s.isConnected());
		return s;
	}
}
