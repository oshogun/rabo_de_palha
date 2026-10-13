package io.github.oshogun.rabodepalha.gametest;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;

/** Command source that keeps every message sent to it, so tests can assert on command feedback. */
final class RecordingCommandSource implements CommandSource {
	private final List<String> messages = new ArrayList<>();

	@Override
	public void sendSystemMessage(Component message) {
		messages.add(message.getString());
	}

	@Override
	public boolean acceptsSuccess() {
		return true;
	}

	@Override
	public boolean acceptsFailure() {
		return true;
	}

	@Override
	public boolean shouldInformAdmins() {
		return false;
	}

	List<String> messages() {
		return List.copyOf(messages);
	}

	boolean anyContains(String text) {
		return messages.stream().anyMatch(m -> m.contains(text));
	}
}
