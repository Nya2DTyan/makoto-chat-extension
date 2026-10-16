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

	/**
	 * Версия мода вместе с суффиксом версии игры, например {@code 1.0.1+26.3}.
	 *
	 * <p>Берётся из манифеста мода, а не из константы в коде: при сборке под
	 * новую версию Minecraft строку не нужно править руками, и в логе игрока
	 * всегда стоит та версия, которая действительно запущена.
	 */
	public static String version() {
		return net.fabricmc.loader.api.FabricLoader.getInstance()
				.getModContainer(MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("неизвестно");
	}
}