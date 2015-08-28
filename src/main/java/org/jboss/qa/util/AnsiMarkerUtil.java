package org.jboss.qa.util;

import org.fusesource.jansi.Ansi;

public final class AnsiMarkerUtil {

	private AnsiMarkerUtil() {
	}

	public static String removeErrorMarker(String str) {
		if (str == null || str.isEmpty()) {
			return str;
		}

		final String errorMarker = Ansi.ansi().fg(Ansi.Color.RED).toString();
		final String defaultMarker = Ansi.ansi().fg(Ansi.Color.DEFAULT).toString();

		final int errorMarkerIndex = str.indexOf(errorMarker);
		if (errorMarkerIndex >= 0) {
			str = str.substring(errorMarkerIndex + errorMarker.length());
		}
		final int defaultMarkerIndex = str.indexOf(defaultMarker);
		if (defaultMarkerIndex >= 0) {
			str = str.substring(0, defaultMarkerIndex);
		}
		return str;
	}
}
