package nya2dtyan.makotochat;

import net.minecraft.resources.Identifier;
import net.minecraft.client.KeyMapping;

/** Общие константы мода. */
public final class ChatTabsMod {

	public static final String MOD_ID = "makoto-chat-extension";

	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MOD_ID);

	private ChatTabsMod() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	/** Категория для наших биндов — чтобы они не терялись среди ванильных. */
	public static KeyMapping.Category keyCategory() {
		return KeyMapping.Category.register(id("keys"));
	}

	public static org.slf4j.Logger logger() {
		return LOGGER;
	}
}