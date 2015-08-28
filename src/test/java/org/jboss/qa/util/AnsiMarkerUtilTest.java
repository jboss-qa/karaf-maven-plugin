package org.jboss.qa.util;

import org.junit.Assert;
import org.junit.Test;

import org.fusesource.jansi.Ansi;

import java.util.UUID;

public class AnsiMarkerUtilTest {

	private static final String errorMarker = Ansi.ansi().fg(Ansi.Color.RED).toString();

	private static final String defaultMarker = Ansi.ansi().fg(Ansi.Color.DEFAULT).toString();

	@Test
	public void testRemoveErrorMarkerNull() throws Exception {
		Assert.assertEquals(null, AnsiMarkerUtil.removeErrorMarker(null));
	}

	@Test
	public void testRemoveErrorMarkerEmpty() throws Exception {
		Assert.assertEquals("", AnsiMarkerUtil.removeErrorMarker(""));
	}

	@Test
	public void testRemoveErrorMarkerBasic() throws Exception {
		final String expected = UUID.randomUUID().toString();
		final String result = AnsiMarkerUtil.removeErrorMarker(errorMarker + expected + defaultMarker);
		Assert.assertEquals(expected, result);
	}

	@Test
	public void testRemoveErrorMarkerNoMarkers() throws Exception {
		final String expected = UUID.randomUUID().toString();
		final String result = AnsiMarkerUtil.removeErrorMarker(expected);
		Assert.assertEquals(expected, result);
	}

	@Test
	public void testRemoveErrorMarkerNoStartMarker() throws Exception {
		final String expected = UUID.randomUUID().toString();
		final String result = AnsiMarkerUtil.removeErrorMarker(expected + defaultMarker);
		Assert.assertEquals(expected, result);
	}

	@Test
	public void testRemoveErrorMarkerNoEndMarker() throws Exception {
		final String expected = UUID.randomUUID().toString();
		final String result = AnsiMarkerUtil.removeErrorMarker(errorMarker + expected);
		Assert.assertEquals(expected, result);
	}

	@Test
	public void testRemoveErrorMarkerJustMarkers() throws Exception {
		final String result = AnsiMarkerUtil.removeErrorMarker(errorMarker + defaultMarker);
		Assert.assertEquals("", result);
	}

	@Test
	public void testRemoveErrorMarkerJustStartMarker() throws Exception {
		final String result = AnsiMarkerUtil.removeErrorMarker(errorMarker);
		Assert.assertEquals("", result);
	}

	@Test
	public void testRemoveErrorMarkerJustEndMarker() throws Exception {
		final String result = AnsiMarkerUtil.removeErrorMarker(defaultMarker);
		Assert.assertEquals("", result);
	}
}
