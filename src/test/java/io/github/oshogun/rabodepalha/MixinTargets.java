package io.github.oshogun.rabodepalha;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.function.Executable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Reads a mixin config and the {@code @Mixin} targets of its mixins as raw resources, without loading any
 * mixin or target class, so a test can then load each target and let Mixin apply the config.
 */
final class MixinTargets {
	private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";

	private MixinTargets() {
	}

	/** Mixin class name to the binary names of its targets, for every mixin in the config. */
	static Map<String, List<String>> of(String config, ClassLoader loader) throws IOException {
		JsonObject json;
		try (InputStream in = loader.getResourceAsStream(config)) {
			if (in == null) {
				throw new IllegalStateException(config + " not on the test classpath");
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				json = JsonParser.parseReader(reader).getAsJsonObject();
			}
		}
		String pkg = json.get("package").getAsString();
		Map<String, List<String>> targets = new LinkedHashMap<>();
		for (String section : new String[] {"mixins", "client", "server"}) {
			if (!json.has(section)) {
				continue;
			}
			for (JsonElement e : json.getAsJsonArray(section)) {
				String mixin = pkg + "." + e.getAsString();
				targets.put(mixin, targetsOf(mixin, loader));
			}
		}
		return targets;
	}

	private static List<String> targetsOf(String mixin, ClassLoader loader) throws IOException {
		byte[] bytes;
		try (InputStream in = loader.getResourceAsStream(mixin.replace('.', '/') + ".class")) {
			if (in == null) {
				throw new IllegalStateException(mixin + ".class not on the test classpath");
			}
			bytes = in.readAllBytes();
		}
		List<String> targets = new ArrayList<>();
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
				if (!MIXIN_DESC.equals(descriptor)) {
					return null;
				}
				return new AnnotationVisitor(Opcodes.ASM9) {
					@Override
					public AnnotationVisitor visitArray(String name) {
						if (!"value".equals(name) && !"targets".equals(name)) {
							return null;
						}
						return new AnnotationVisitor(Opcodes.ASM9) {
							@Override
							public void visit(String ignored, Object value) {
								if (value instanceof Type type) {
									targets.add(type.getClassName());
								} else if (value instanceof String target) {
									targets.add(target.replace('/', '.'));
								}
							}
						};
					}
				};
			}
		}, ClassReader.SKIP_CODE);
		if (targets.isEmpty()) {
			throw new IllegalStateException(mixin + " has no @Mixin target");
		}
		return targets;
	}

	/**
	 * One check per (mixin, target): loading the target without initializing it runs the mixin transform,
	 * so a failed injection throws here while no static initializer of a render class runs.
	 */
	static List<Executable> loadChecks(Map<String, List<String>> mixins, ClassLoader loader) {
		List<Executable> checks = new ArrayList<>();
		mixins.forEach((mixin, targets) -> {
			for (String target : targets) {
				checks.add(() -> {
					try {
						Class.forName(target, false, loader);
					} catch (Throwable t) {
						throw new AssertionError(mixin + " failed to apply to " + target + ": " + rootMessage(t), t);
					}
				});
			}
		});
		return checks;
	}

	private static String rootMessage(Throwable t) {
		StringBuilder message = new StringBuilder(String.valueOf(t));
		for (Throwable cause = t.getCause(); cause != null && cause != t; cause = cause.getCause()) {
			message.append(" <- ").append(cause);
			t = cause;
		}
		return message.toString();
	}
}
