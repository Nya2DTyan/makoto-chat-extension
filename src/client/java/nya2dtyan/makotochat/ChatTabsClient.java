package nya2dtyan.makotochat;

import net.fabricmc.api.ClientModInitializer;

/**
 * Точка входа мода: только связывание событий. Логика живёт в {@link ChatRouter},
 * чтобы её можно было проверять без запуска игры.
 *
 * <p><b>Про создание биндов — важно для сборки под две версии игры.</b> Бинды
 * создаются конструктором {@code KeyMapping(имя, код, категория)}, без
 * {@code InputConstants.Type} в аргументах. Этот конструктор сам подставляет
 * тип клавиатуры: в 26.2 это {@code KEYSYM}, в 26.3 — {@code KEYBOARD}
 * (константу переименовали). Вызов с типом напрямую не собрался бы одним и тем
 * же кодом ни под одну из версий. Сверено по байткоду {@code KeyMapping.<init>}
 * в обеих версиях: трёхаргументная форма делегирует именно на клавиатурный тип.
 *
 * <p>Модуля {@code fabric-keybinding-api-v1} в Fabric API для 26.x больше нет:
 * {@link net.minecraft.client.KeyMapping} регистрирует себя сам при создании,
 * а ванильный {@code Options} подхватывает его через {@code KeyMapping.ALL}.
 * Поэтому бинды создаются напрямую.
 */
public class ChatTabsClient implements ClientModInitializer {

	/** -1 = клавиша не назначена; игрок назначит её в настройках управления. */
	private static final int UNBOUND = -1;

	private static net.minecraft.client.KeyMapping nextTabKey;
	private static net.minecraft.client.KeyMapping previousTabKey;
	private static net.minecraft.client.KeyMapping toggleOverlayKey;

	@Override
	public void onInitializeClient() {
		ChatRouter router = ChatRouter.getInstance();

		// Тип клавиатуры не указываем: конструктор подставит его сам, и код
		// остаётся общим для 26.2 и 26.3 (см. описание класса).
		var category = ChatTabsMod.keyCategory();
		nextTabKey = new net.minecraft.client.KeyMapping("key.makoto_chat_extension.next_tab", UNBOUND, category);
		previousTabKey = new net.minecraft.client.KeyMapping("key.makoto_chat_extension.previous_tab", UNBOUND, category);
		toggleOverlayKey = new net.minecraft.client.KeyMapping("key.makoto_chat_extension.toggle_overlay", UNBOUND,
				category);

		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.CHAT.register(
				(message, signedChat, sender, params, receptionTimestamp) -> router.onIncomingChat(message.getString()));
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register(
				(message, overlay) -> router.onIncomingSystemMessage(message.getString(), overlay));

		// Собственный ввод раскладывается по вкладкам так же, как входящий:
		// иначе отправленное в «ЛС» или в город не появилось бы в своей вкладке.
		net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.CHAT.register(router::onOutgoingChat);
		net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.COMMAND.register(router::onOutgoingCommand);

		// На вкладках «Город» и «Глобальный» подставляем префикс сами: без него
		// сервер не считает реплику городской или глобальной. MODIFY_CHAT
		// вызывается до CHAT, поэтому роутер увидит сообщение уже с префиксом
		// и разложит его в нужную вкладку.
		net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.MODIFY_CHAT.register(
				message -> {
					String prepared = router.withCityPrefix(message);
					return router.withGlobalPrefix(prepared);
				});

		net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register(
				(dispatcher, access) -> ChatTabsCommands.register(dispatcher, access));

		// Пересборка списка отрисовки отложена до тика: в момент события
		// сообщение могло ещё не дойти до чата.
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
				.register(client -> ChatTabFilter.getInstance().onClientTick());

		TabOverlay.register();

		// Фильтр ставится при входе в мир: до этого ChatComponent не существует.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
				(handler, sender, client) -> {
					ChatTabFilter.getInstance().refresh();
					ChatRouter.getInstance().store().clearAll();

					// Видно сразу при входе: значит мод загрузился и вкладки на месте.
					// Без этой строки «мод не загрузился» и «вкладок не видно» выглядят одинаково.
					client.gui.hud.getChat().addClientSystemMessage(
							net.minecraft.network.chat.Component.literal(
									"[Chat Tabs] Общий / ЛС / Город / CO — переключение кликом по полоске внизу или клавишами."));

					if (!ChatTabFilter.getInstance().isInstalled()) {
						// Явный сигнал: вкладки нарисованы, но разделение чата не работает.
						ChatTabsMod.logger().warn("Фильтр чата не установлен — разделение вкладок не будет работать");
					}
				});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
				(handler, client) -> ChatTabFilter.getInstance().invalidateCache());

		ChatTabsMod.logger().info("Chat Tabs: вкладки инициализированы, версия {}", ChatTabsMod.version());
	}

	/**
	 * Разбирает нажатые наши клавиши. Вызывается каждый кадр из рендера HUD.
	 *
	 * @return {@code true}, если событие клавиатуры можно проглотить
	 */
	public static boolean handleKeyPress() {
		ChatRouter router = ChatRouter.getInstance();
		boolean handled = false;
		while (nextTabKey != null && nextTabKey.consumeClick()) {
			router.cycleNext();
			handled = true;
		}
		while (previousTabKey != null && previousTabKey.consumeClick()) {
			router.cyclePrevious();
			handled = true;
		}
		while (toggleOverlayKey != null && toggleOverlayKey.consumeClick()) {
			router.toggleOverlay();
			handled = true;
		}
		return handled;
	}

	/** Статус для HUD: имя активной вкладки. */
	public static net.minecraft.network.chat.Component overlayStatus() {
		return net.minecraft.network.chat.Component.literal(ChatRouter.getInstance().activeTab().displayName());
	}
}