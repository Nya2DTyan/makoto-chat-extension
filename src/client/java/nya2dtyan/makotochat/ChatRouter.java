package nya2dtyan.makotochat;

import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Принимает сообщения и раскладывает их по вкладкам.
 *
 * <p>Требование, ради которого всё затевалось: <b>вкладки обновляются независимо
 * от того, какая открыта</b>. Роутер не знает, что сейчас видно, — он просто
 * пишет в нужную вкладку. Поэтому переключение вкладки не «проматывает» то,
 * что пришло, пока открыта другая.
 *
 * <p>Ошибки плагинов и отказы (CoreProtect, «игрок офлайн», неизвестная команда)
 * не должны попадать в общий чат шумом, но и теряться не могут: такие строки
 * уходят в {@link ChatChannel#CORE_PROTECT} и общий чат одновременно, с
 * пометкой канала в начале. Так ничего не пропадает, но и не мешает.
 */
public final class ChatRouter {

	private static ChatRouter instance;

	private final TabStore store = new TabStore();

	/** Метки команд, по которым опознаём вывод CoreProtect. */
	private static final List<String> CORE_COMMAND_PREFIXES = List.of("/co ", "/coreprotect ", "/co i", "/co info",
			"/co lookup", "/co search", "/co rollback", "/co undo", "/co redo");

	private boolean overlayVisible = true;

	private ChatRouter() {
	}

	public static synchronized ChatRouter getInstance() {
		if (instance == null) {
			instance = new ChatRouter();
		}
		return instance;
	}

	/** Подписка на события вынесена отдельно, чтобы роутер оставался чистым. */
	public void register() {
		// События подписываются в ChatTabsClient; здесь держим точку расширения.
	}

	/**
	 * Префикс городского ввода, который требует плагин города.
	 *
	 * <p>Сервер понимает городскую реплику только с «?», поэтому на вкладке
	 * «Город» подставляем его сам. В поле ввода при этом ничего не видно:
	 * подстановка происходит на отправке, а не в том, что игрок видит.
	 */
	public static final String CITY_PREFIX = "?";

	/**
	 * Дописывает городской префикс, если активна вкладка «Город».
	 *
	 * <p>Вызывается на отправке сообщения. Ничего не меняет, если префикс уже
	 * стоит, строка пустая, это команда или активна не городская вкладка —
	 * иначе игрок не смог бы написать команду {@code /co i}, находясь на «Городе».
	 *
	 * @return строку, которую нужно отправить на сервер
	 */
	public String withCityPrefix(String message) {
		return withChannelPrefix(message, MessageCategory.CITY, CITY_PREFIX);
	}

	/** Префикс глобального ввода, который требует сервер. */
	public static final String GLOBAL_PREFIX = "!";

	/**
	 * Дописывает «!», если активна вкладка «Глобальный».
	 *
	 * <p>Работает ровно как городской префикс: игрок печатает обычный текст,
	 * а сервер получает его с «!». В поле ввода «!» не виден.
	 */
	public String withGlobalPrefix(String message) {
		return withChannelPrefix(message, MessageCategory.GLOBAL_CHAT, GLOBAL_PREFIX);
	}

	/**
	 * Вкладка «под одну категорию»: показывает её, но не обычный чат.
	 *
	 * <p>Признак нужен для подстановки префиксов и для своего ввода. Раньше
	 * сравнивали саму вкладку с каналом, но вкладок теперь сколько угодно:
	 * «Город» и «Мой город» одинаково про город, а «Общий» — нет, хотя тоже
	 * показывает городской чат. Поэтому смотрим на набор категорий: если вкладка
	 * заведена под эту категорию и не содержит обычного чата, значит её и имеют
	 * в виду, когда на ней печатают.
	 */
	private boolean activeTabDedicatedTo(MessageCategory category) {
		ChatTab active = TabConfig.getInstance().activeTab();
		return active.has(category) && !active.has(MessageCategory.CHAT);
	}

	/**
	 * Общая часть подстановки префикса: только на своей вкладке, только для
	 * обычного текста, и никогда дважды.
	 */
	private String withChannelPrefix(String message, MessageCategory category, String prefix) {
		if (message == null || message.isBlank()) {
			return message;
		}
		// Команда должна уйти командой: с «?» или «!» сервер её не выполнит.
		if (message.startsWith("/")) {
			return message;
		}
		if (!activeTabDedicatedTo(category)) {
			return message;
		}
		if (message.startsWith(prefix)) {
			return message;
		}
		return prefix + message;
	}

	/** Входящее сообщение игрока в общий чат. */
	public void onIncomingChat(String rawText) {
		accept(rawText, false, false);
	}

	/**
	 * Входящее системное сообщение сервера.
	 *
	 * @param overlay {@code true}, если это оверлей (строка поверх экрана) —
	 *     такие сообщения не засоряют историю чата и в отдельные вкладки не идут
	 */
	public void onIncomingSystemMessage(String rawText, boolean overlay) {
		if (overlay) {
			// Оверлей — это подсказки и заголовки, а не переписка. В историю вкладок их не пишем.
			return;
		}
		accept(rawText, false, true);
	}

	/** Собственное сообщение, отправленное в общий чат. */
	public void onOutgoingChat(String message) {
		if (message == null || message.isBlank()) {
			return;
		}
		String trimmed = message.trim();
		// Команды сюда не попадают: они идут через onOutgoingCommand.
		accept(trimmed, true, true);
	}

	/** Собственная команда — её вывод почти наверняка от CoreProtect. */
	public void onOutgoingCommand(String commandLine) {
		if (commandLine == null || commandLine.isBlank()) {
			return;
		}
		String trimmed = commandLine.trim();
		accept(trimmed, true, true);
	}

	/**
	 * Единая точка раскладки сообщения по вкладкам.
	 *
	 * @param rawText исходный текст (может содержать цветовые коды)
	 * @param outOfInput {@code true}, если это наш собственный ввод
	 * @param fromSystem {@code true}, если строка пришла из системного чата сервера
	 */
	private void accept(String rawText, boolean outOfInput, boolean fromSystem) {
		if (rawText == null || rawText.isBlank()) {
			return;
		}

		// CoreProtect-команды опознаём по исходному виду, до нормализации:
		// сервер может печатать «/co i» прямо в чате.
		if (isCoreCommand(MessageCategory.normalize(rawText))) {
			record(rawText, MessageCategory.CORE_PROTECT);
			return;
		}

		MessageCategory category = MessageClassifier.classify(rawText, outOfInput);

		// Отказы и сообщения об ошибках — полезная информация, но не «обычный чат».
		// Их ищут в CoreProtect-вкладке, где их не смешать с чужой репликой.
		if (category == MessageCategory.CHAT && fromSystem && looksLikeServerNotice(rawText)) {
			record(rawText, MessageCategory.CORE_PROTECT);
			return;
		}

		// Городской чат по договорённости с плагином идёт с «?», поэтому такая же
		// реплика от других игроков может прийти в обычный чат. Держим её в двух
		// категориях: во вкладках, которым нужен город, она появится, а в остальных
		// останется обычной репликой. Раньше это делалось записью в два канала.
		boolean alsoCity = category == MessageCategory.CHAT && MessageClassifier.isCityMarked(rawText);

		// Своё собственное сообщение ничем не отличается от обычного текста:
		// сервер добавит ник и метку только тогда, когда вернёт его эхом. Текст,
		// отправленный на вкладку локального чата, иначе классифицировался бы как
		// обычная реплика и не попал бы в неё — вкладка осталась бы пустой.
		if (outOfInput && category == MessageCategory.CHAT
				&& activeTabDedicatedTo(MessageCategory.LOCAL)) {
			category = MessageCategory.LOCAL;
		}

		// Дальше раскладывать нечего: «Общий» и любая другая вкладка показывают
		// сообщение сами, если его категория есть в их наборе.
		record(rawText, category);
		if (alsoCity) {
			record(rawText, MessageCategory.CITY);
		}
	}

	/**
	 * Записывает сообщение во вкладки и сообщает фильтру, что у него за категория.
	 *
	 * <p>Хранилище нужно для счётчиков непрочитанного, а сопоставление
	 * «текст → категория» — чтобы фильтр чата показывал ровно то, что решил
	 * роутер, даже когда решение нестандартное (строка про CoreProtect, которую
	 * сервер напечатал в общий чат).
	 *
	 * <p>Кладём во <b>все</b> вкладки, которые показывают эту категорию: вкладка —
	 * это набор категорий, и счётчик должен считать то же, что игрок увидит,
	 * открыв её.
	 */
	private void record(String rawText, MessageCategory category) {
		RoutedMessage message = makeMessage(rawText);
		for (ChatTab tab : TabConfig.getInstance().tabs()) {
			if (tab.has(category)) {
				store.add(tab, message);
			}
		}
		ChatTabFilter.getInstance().record(rawText, category);
		// Список отрисовки надо пересобрать: новое сообщение может попасть
		// в активную вкладку, а ванильный чат сам её не пересобирает.
		// Отложенно до тика — на момент события сообщение могло ещё не
		// доехать до чата, и пересборка его бы не увидела.
		ChatTabFilter.getInstance().markDirty();
	}

	/**
	 * Тик, когда игрок закрыл экран чата.
	 *
	 * <p>Это единственный вход для видимости полоски вкладок в HUD: она
	 * раскрывается при открытии чата и гаснет после его закрытия, как старые
	 * сообщения. Приход новых сообщений её <b>не</b> раскрывает — иначе полоска
	 * появлялась бы вместе с каждой репликой, хотя игрок чат не открывал.
	 *
	 * <p>Значение {@link Integer#MIN_VALUE} означает «чат ещё не открывали»:
	 * вкладки в HUD тогда скрыты. Счётчик тот же, что у ванильного чата
	 * ({@code Hud.getGuiTicks()}).
	 */
	private int chatClosedTick = Integer.MIN_VALUE;

	/** Запоминает момент закрытия чата — от него считается затухание полоски. */
	public void onChatClosed(int nowTick) {
		chatClosedTick = nowTick;
	}

	/** Тик, когда чат был закрыт; {@link Integer#MIN_VALUE} — ещё не открывали. */
	public int chatClosedTick() {
		return chatClosedTick;
	}

	/** Сброс перед новым миром: в нём чат ещё не открывали, полоски быть не должно. */
	public void resetChatState() {
		chatClosedTick = Integer.MIN_VALUE;
	}

	private RoutedMessage makeMessage(String rawText) {
		long now = System.currentTimeMillis();
		return new RoutedMessage(net.minecraft.network.chat.Component.literal(rawText), null, now,
				store.nextLineOrder());
	}

	private static boolean isCoreCommand(String normalized) {
		if (normalized.startsWith("/")) {
			for (String prefix : CORE_COMMAND_PREFIXES) {
				if (normalized.startsWith(prefix)) {
					return true;
				}
			}
			return false;
		}
		return false;
	}

	/**
	 * Отказ или сообщение об ошибке от сервера.
	 *
	 * <p>Само по себе это ещё не CoreProtect: такие строки бывают и от других
	 * плагинов — например, фильтр мата («Ошибка! Было использовано плохое слово
	 * …»). Поэтому отказ считается сообщением CoreProtect только тогда, когда
	 * в нём есть собственные маркеры CO. Иначе он остаётся в «Общем» и не
	 * засоряет вкладку CoreProtect чужими ошибками.
	 */
	private static boolean looksLikeServerNotice(String rawText) {
		String normalized = MessageCategory.normalize(rawText);
		if (!MessageClassifier.looksLikeCoreProtect(normalized)) {
			return false;
		}
		return normalized.contains("не найден") || normalized.contains("не существует")
				|| normalized.contains("недоступно") || normalized.contains("нет прав")
				|| normalized.contains("неправильн") || normalized.contains("ошибка")
				|| normalized.contains("не удалось") || normalized.contains("invalid");
	}

	public ChatTab activeTab() {
		return TabConfig.getInstance().activeTab();
	}

	public TabStore store() {
		return store;
	}

	public void cycleNext() {
		select(TabConfig.getInstance().cycle(1));
	}

	public void cyclePrevious() {
		select(TabConfig.getInstance().cycle(-1));
	}

	/**
	 * Делает вкладку активной и пересобирает видимую часть чата.
	 *
	 * <p>Вкладку 🔧 сюда не передают: это не лента сообщений, а вход в меню
	 * настроек, и открывает его сама полоска.
	 */
	public void select(ChatTab tab) {
		if (tab == null) {
			return;
		}
		// Скрытую вкладку нельзя сделать активной — её нет на экране.
		if (!tab.isVisible()) {
			return;
		}
		TabConfig.getInstance().setActiveTab(tab);
		store.markRead(tab);
		onTabChanged(tab);
	}

	/**
	 * Пересобирает видимую часть чата после смены вкладки.
	 *
	 * <p>Без этого список отрисованных строк остался бы от прежней вкладки:
	 * ванильный чат собирает его один раз и сам не пересобирает, потому что
	 * «какие сообщения показывать» у него меняться не должно. Отсюда было
	 * одинаковое наполнение во всех вкладках.
	 */
	private void onTabChanged(ChatTab tab) {
		ChatTabsMod.logger().debug("Вкладка -> {}", tab.name());
		ChatTabFilter.getInstance().rebuild();
	}

	public void toggleOverlay() {
		overlayVisible = !overlayVisible;
	}

	public boolean overlayVisible() {
		return overlayVisible;
	}

	/** Клиент может быть недоступен в тестах — отсюда проверка на null. */
	static Minecraft minecraft() {
		return Minecraft.getInstance();
	}
}