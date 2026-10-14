package io.github.oshogun.rabodepalha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Checks the Sodium version pin in fabric.mod.json against Fabric Loader's own predicate semantics. */
class SodiumCompatPinTest {
	private static JsonObject modJson;
	private static List<VersionPredicate> breaks;

	@BeforeAll
	static void load() throws IOException, VersionParsingException {
		modJson = findOwnModJson();
		JsonElement sodium = modJson.getAsJsonObject("breaks").get("sodium");
		assertNotNull(sodium, "breaks.sodium missing");
		breaks = new ArrayList<>();
		if (sodium.isJsonArray()) {
			for (JsonElement e : sodium.getAsJsonArray()) {
				breaks.add(VersionPredicate.parse(e.getAsString()));
			}
		} else {
			breaks.add(VersionPredicate.parse(sodium.getAsString()));
		}
	}

	private static JsonObject findOwnModJson() throws IOException {
		Enumeration<URL> urls = SodiumCompatPinTest.class.getClassLoader().getResources("fabric.mod.json");
		while (urls.hasMoreElements()) {
			try (Reader reader = new InputStreamReader(urls.nextElement().openStream(), StandardCharsets.UTF_8)) {
				JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
				if (json.has("id") && RaboDePalha.MOD_ID.equals(json.get("id").getAsString())) {
					return json;
				}
			}
		}
		throw new IllegalStateException("rabodepalha fabric.mod.json not on the test classpath");
	}

	private static boolean broken(String version) throws VersionParsingException {
		Version v = Version.parse(version);
		return breaks.stream().anyMatch(p -> p.test(v));
	}

	@Test
	void testedReleaseIsAllowed() throws VersionParsingException {
		assertFalse(broken("0.9.2+mc26.3"));
	}

	@Test
	void olderReleaseIsBroken() throws VersionParsingException {
		assertTrue(broken("0.9.1+mc26.3"));
	}

	@Test
	void nextPreReleaseIsBroken() throws VersionParsingException {
		assertTrue(broken("0.9.3-alpha.1+mc26.3"));
	}

	@Test
	void laterReleasesAreBroken() throws VersionParsingException {
		assertTrue(broken("0.9.3+mc26.3"));
		assertTrue(broken("0.10.0+mc26.3"));
	}

	@Test
	void sodiumIsSuggestedNotRequired() {
		assertEquals("*", modJson.getAsJsonObject("suggests").get("sodium").getAsString());
		JsonObject depends = modJson.getAsJsonObject("depends");
		assertFalse(depends != null && depends.has("sodium"));
	}
}
