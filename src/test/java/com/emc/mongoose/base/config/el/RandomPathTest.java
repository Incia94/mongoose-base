package com.emc.mongoose.base.config.el;

import org.junit.Test;

import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

public class RandomPathTest {

	@Test
	public final void testRandomPath()
					throws Exception {
		final var pathPattern = Pattern.compile("(/[0-9a-f]){1,2}");
		try (
						final var rndPathInput = CompositeExpressionInputBuilder
										.newInstance()
										.expression("/${path:random(16, 2)}")
										.build()) {
			for (var i = 0; i < 1000; i++) {
				final var path = rndPathInput.get();
				final var matcher = pathPattern.matcher(path);
				assertTrue(path, matcher.find());
			}
		}
	}
}
