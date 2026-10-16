package nya2dtyan.makotochat;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * Полоска вкладок под чатом, переключение — кликом мыши.
 *
 * <p>Вкладки рисуются в двух местах, и это не дублирование, а необходимость:
 *
 * <ul>
 *   <li><b>В игре</b> — слой HUD, под блоком чата. Здесь курсор захвачен
 *       (прячется и закрепляется в центре окна), поэтому кликнуть по вкладке
 *       физически нечем — переключение клавишами.
 *   <li><b>В открытом чате</b> — слой экрана, над полем ввода. Здесь курсор
 *       свободен, и вкладки кликабельны.
 * </ul>
 *
 * <p>Раньше клики ловились в HUD через {@code MouseHandler.isLeftPressed()}.
 * Это было неверно: при захвате мыши координаты курсора бесполезны, а в
 * открытом чате это поле не обновляется — клик не срабатывал нигде. Хуже, в
 * игре левая кнопка означает удар или копание, и вкладка переключалась бы на
 * каждый клик в мире.
 */
public final class TabOverlay {

	/** Высота полоски вкладок в GUI-пикселях. */
	private static final int BAR_HEIGHT = 12;

	/** Отступ от левого края до первой вкладки. */
	private static final int MARGIN_X = 4;

	/** Зазор между вкладками. */
	private static final int TAB_GAP = 2;

	/** Отступ между нижним краем чата и полоской вкладок. */
	private static final int GAP_ABOVE = 2;

	/**
	 * Нижний отступ чата — копия приватной константы {@code ChatComponent.BOTTOM_MARGIN}.
	 *
	 * <p>Значение сверено с ванильным классом (равно {@code 40}): именно из
	 * него ванильный {@code ChatComponent} считает верх блока чата в игре. Поле
	 * приватное, поэтому берём числом. Значение одинаково в 26.2 и 26.3.
	 */
	private static final int BOTTOM_MARGIN = 40;

	/** Высота поля ввода в экране чата — из неё считаем позицию вкладок. */
	private static final int CHAT_INPUT_HEIGHT = 12;

	/**
	 * Через сколько тиков чат полностью гаснет — 10 секунд при 20 тиках в секунду.
	 *
	 * <p>Совпадает с ванильной формулой затухания {@code ChatComponent$AlphaCalculator}:
	 * множитель {@code clamp(1 - (age / 200.0) * 10, 0, 1)} обнуляется при
	 * {@code age = 200}, то есть ровно через 10 секунд после последнего сообщения.
	 */
	private static final int CHAT_FADE_TICKS = 200;

	/** Подпись служебной ячейки — входа в меню настроек. */
	static final String TAB_SETTINGS_LABEL = "🔧";

	/** Цвет подписи активной вкладки: ARGB, альфа обязательна. */
	static final int TAB_TEXT_ACTIVE = 0xFFFFFFFF;

	/** Цвет подписи неактивной вкладки: ARGB, альфа обязательна. */
	static final int TAB_TEXT_INACTIVE = 0xFFB0B0B0;

	/** Фон активной вкладки — светлее и непрозрачнее. */
	static final int TAB_BG_ACTIVE = 0xC0000000;

	/** Фон неактивной вкладки. */
	static final int TAB_BG_INACTIVE = 0x80000000;

	/**
	 * Сколько прямоугольников вкладок держим в массивах геометрии.
	 *
	 * <p>Размер с запасом относительно числа каналов: массив не должен падать,
	 * если каналов добавят.
	 */
	private static final int MAX_TRACKED_CHANNELS = 16;

	private static final int[] BOX_X = new int[MAX_TRACKED_CHANNELS];
	private static final int[] BOX_Y = new int[MAX_TRACKED_CHANNELS];
	private static final int[] BOX_W = new int[MAX_TRACKED_CHANNELS];
	private static final int[] BOX_H = new int[MAX_TRACKED_CHANNELS];

	private TabOverlay() {
	}

	public static void register() {
		HudElementRegistry.addLast(ChatTabsMod.id("tab_bar"), TabOverlay::extractHudRenderState);
		registerChatScreenLayer();
	}

	/**
	 * Слой внутри экрана чата: рисуем над полем ввода и ловим там клики.
	 *
	 * <p>Отдельный слой нужен потому, что в экране чата HUD не рисуется, а
	 * курсор, наоборот, свободен — только здесь клики по вкладкам возможны.
	 */
	private static void registerChatScreenLayer() {
		// События экранов бывают двух видов: глобальные (BEFORE_INIT /
		// AFTER_INIT) и per-screen (нужен уже созданный экземпляр экрана).
		// Экземпляр ChatScreen заранее получить нельзя, поэтому ловим момент
		// открытия чата и подписываем per-screen события уже на нём.
		ScreenEvents.BEFORE_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof ChatScreen) {
				// Прямоугольники строим сразу: первый клик может прийти раньше,
				// чем отработает первый кадр отрисовки этого экрана.
				build(minecraft.font, layoutYInChat(height));
				registerPerScreen((ChatScreen) screen);
			}
		});

		// Клавиши работают и когда вкладки скрыты, поэтому вне зависимости от чата.
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) ->
				ChatTabsClient.handleKeyPress());
	}

	/** Подписка на отрисовку, клики и закрытие конкретного открытого экрана чата. */
	private static void registerPerScreen(ChatScreen screen) {
		ScreenEvents.afterExtract(screen)
				.register((s, graphics, mouseX, mouseY, delta) -> {
					if (ChatRouter.getInstance().overlayVisible()) {
						draw(graphics, Minecraft.getInstance().font, layoutYInChat(s.height));
					}
				});

		// Закрытие чата — единственный вход для видимости полоски в HUD: от
		// этого момента она гаснет, как старые сообщения. Подписка нужна
		// отдельно от отрисовки, потому что экран чата к моменту затухания
		// уже закрыт и рисовать в нём нечего.
		ScreenEvents.remove(screen).register(closed -> {
			Minecraft minecraft = ChatRouter.minecraft();
			if (minecraft != null && minecraft.gui != null && minecraft.gui.hud != null) {
				ChatRouter.getInstance().onChatClosed(minecraft.gui.hud.getGuiTicks());
			}
		});

		// true — клик уходит дальше ванильному чату, false — обработан нами.
		ScreenMouseEvents.allowMouseClick(screen)
				.register((s, event) -> {
					if (!ChatRouter.getInstance().overlayVisible()) {
						return true;
					}
					// Геометрию пересобираем здесь: клик может прийти раньше первого
					// кадра отрисовки, и прямоугольники были бы от HUD-слоя.
					build(Minecraft.getInstance().font, layoutYInChat(s.height));
					return !selectAt((int) event.x(), (int) event.y());
				});
	}

	/**
	 * Слой HUD — вкладки под чатом в игре.
	 *
	 * <p>Раскрываются только вместе с открытием чата, а после его закрытия
	 * гаснут — по той же формуле затухания, что и старые сообщения
	 * ({@value #CHAT_FADE_TICKS} тиков). Приход новых сообщений полоску
	 * <b>не</b> раскрывает: она видна ровно в том окне, которое началось
	 * закрытием чата, поэтому не появляется вместе с каждой репликой, когда
	 * игрок чат не открывал.
	 */
	private static void extractHudRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		// Клавиши обрабатываем всегда, даже когда оверлей скрыт — иначе переключение
		// перестало бы работать после его выключения.
		ChatTabsClient.handleKeyPress();

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gui == null || !ChatRouter.getInstance().overlayVisible()) {
			return;
		}
		if (minecraft.gui.hud.isHidden() || minecraft.gui.hud.getChat().isChatFocused()) {
			return;
		}

		float fade = tabBarFade(minecraft.gui.hud.getGuiTicks());
		if (fade <= 0.0f) {
			return;
		}
		draw(graphics, minecraft.font, layoutY(graphics.guiHeight()), fade);
	}

	/**
	 * Насколько сейчас видна полоска вкладок в HUD.
	 *
	 * <p>Возраст считается от момента закрытия чата: {@code age = now - chatClosedTick}.
	 * Затухающий множитель — {@code clamp((1 - age / 200) * 10, 0, 1)} в квадрате;
	 * формула и её порядок операций сверены с байткодом
	 * {@code ChatComponent$AlphaCalculator}, чтобы полоска гасла так же, как
	 * старые сообщения в чате.
	 *
	 * <p>Если чат в этом мире ещё не открывали, полоска скрыта: без этого она
	 * висела бы на экране с самого входа в мир.
	 *
	 * @return {@code 1} — полоска видна полностью, {@code 0} — не видна вовсе
	 */
	static float tabBarFade(int nowTick) {
		int closedAt = ChatRouter.getInstance().chatClosedTick();
		if (closedAt == Integer.MIN_VALUE) {
			// Чат ещё не открывали — в HUD показывать нечего.
			return 0.0f;
		}
		int age = nowTick - closedAt;
		if (age < 0) {
			// Смена мира обнуляет счётчик тиков: считаем, что чат закрыли только что.
			return 1.0f;
		}
		if (age >= CHAT_FADE_TICKS) {
			return 0.0f;
		}
		// Та же формула, что у ванильного рендера, чтобы вкладки и чат
		// исчезали в один и тот же момент. Скобки обязательны: по байткоду
		// сначала вычитание, потом умножение на 10 — иначе ноль выходил бы
		// на 20-м тике вместо 200-го, то есть вкладки гасли бы за секунду.
		float linear = (float) ((1.0 - age / (double) CHAT_FADE_TICKS) * 10.0);
		if (linear >= 1.0f) {
			return 1.0f;
		}
		if (linear <= 0.0f) {
			return 0.0f;
		}
		return linear * linear;
	}

	/**
		 * Рисует полоску и запоминает геометрию для проверки попаданий.
		 *
		 * <p>Вкладки берутся из настройки игрока ({@link TabConfig}), а не из
		 * перечисления категорий: их можно добавлять, удалять и переименовывать.
		 * Последняя ячейка полоски — не вкладка, а вход в меню настроек (🔧).
		 *
		 * @param fade видимость чата 0..1 — на неё умножается альфа всех цветов,
		 *     чтобы вкладки гасли ровно вместе с чатом
		 */
		private static void draw(GuiGraphicsExtractor graphics, Font font, int y, float fade) {
			ChatRouter router = ChatRouter.getInstance();
			int x = MARGIN_X;

			// Рисуем только видимые вкладки: скрытая не должна занимать место
			// в полоске и попадать в переключение по кругу.
			List<ChatTab> tabs = barTabs();
			for (int i = 0; i < tabs.size(); i++) {
				ChatTab tab = tabs.get(i);
				boolean active = tab == router.activeTab();
				int unread = router.store().unreadCount(tab, router.activeTab());

				String label = unread > 0 ? tab.label() + " " + unread : tab.label();
				int width = font.width(label) + 8;

				// Активная вкладка — светлее и непрозрачнее, остальные приглушены.
				graphics.fill(x, y, x + width, y + BAR_HEIGHT,
						withAlpha(active ? TAB_BG_ACTIVE : TAB_BG_INACTIVE, fade));

				// Цвет текста — ARGB, и text() берёт альфу из старшего байта.
				// У 0xFFFFFF альфа = 0, то есть текст полностью прозрачен: подписи
				// вкладок были не видны, хотя фон рисовался. Поэтому 0xFF обязателен.
				graphics.text(font, label, x + 4, y + 2,
						withAlpha(active ? TAB_TEXT_ACTIVE : TAB_TEXT_INACTIVE, fade), true);

				remember(i, x, y, width);
				x += width + TAB_GAP;
			}

			// 🔧 — служебная ячейка, она не бывает активной вкладкой.
			int settingsWidth = font.width(TAB_SETTINGS_LABEL) + 8;
			graphics.fill(x, y, x + settingsWidth, y + BAR_HEIGHT, withAlpha(TAB_BG_INACTIVE, fade));
			graphics.text(font, TAB_SETTINGS_LABEL, x + 4, y + 2, withAlpha(TAB_TEXT_INACTIVE, fade), true);
			remember(tabs.size(), x, y, settingsWidth);
		}

		/** Запоминает прямоугольник ячейки полоски — для проверки попаданий клика. */
		private static void remember(int slot, int x, int y, int width) {
			if (slot < MAX_TRACKED_CHANNELS) {
				BOX_X[slot] = x;
				BOX_Y[slot] = y;
				BOX_W[slot] = width;
				BOX_H[slot] = BAR_HEIGHT;
			}
		}

		/** Видимые вкладки в порядке полоски. */
		static List<ChatTab> barTabs() {
			return TabConfig.getInstance().visibleTabs();
		}

		/** Рисует полоску с полной видимостью — в открытом чате, где гасить нечего. */
		private static void draw(GuiGraphicsExtractor graphics, Font font, int y) {
			draw(graphics, font, y, 1.0f);
		}

		/**
		 * Умножает существующую альфу ARGB-цвета на видимость.
		 *
		 * <p>Цвета заданы с полной альфой ({@code 0xFF} в старшем байте), а здесь она
		 * домножается на затухание чата: так вкладки исчезают ровно тогда, когда
		 * исчезает сам чат, а не по своему отдельному таймеру.
		 */
		static int withAlpha(int argb, float fade) {
			float clamped = Math.max(0.0f, Math.min(1.0f, fade));
			int alpha = (int) ((argb >>> 24) * clamped);
			return (alpha << 24) | (argb & 0x00FFFFFF);
		}

	/** Заполняет прямоугольники ячеек без отрисовки — для проверки клика. */
	private static void build(Font font, int y) {
		int x = MARGIN_X;
		List<ChatTab> tabs = barTabs();
		for (int i = 0; i < tabs.size(); i++) {
			ChatTab tab = tabs.get(i);
			int unread = ChatRouter.getInstance().store().unreadCount(tab, ChatRouter.getInstance().activeTab());
			String label = unread > 0 ? tab.label() + " " + unread : tab.label();

			remember(i, x, y, font.width(label) + 8);
			x += BOX_W[i] + TAB_GAP;
		}
		remember(tabs.size(), x, y, font.width(TAB_SETTINGS_LABEL) + 8);
	}

	/**
	 * Позиция полоски в игре — под блоком чата.
	 *
	 * <p>Вынесено отдельной функцией без зависимостей от GUI, потому что именно
	 * здесь раньше была ошибка: полоска считалась от {@code getHeight()} и
	 * попадала на середину чата. Теперь формула видна и проверяется тестом.
	 *
	 * @param screenHeight высота экрана в GUI-пикселях
	 * @return координата Y верхней границы полоски
	 */
	static int layoutY(int screenHeight) {
		int y = screenHeight - BOTTOM_MARGIN + GAP_ABOVE;
		// Не даём полоске уехать за нижний край экрана на низких разрешениях.
		if (y + BAR_HEIGHT > screenHeight) {
			y = screenHeight - BAR_HEIGHT;
		}
		return y;
	}

	/**
	 * Позиция полоски в экране чата — над полем ввода.
	 *
	 * <p>Поле ввода занимает последние {@value #CHAT_INPUT_HEIGHT} пикселей экрана
	 * (проверено в байткоде {@code ChatScreen.init()}), поэтому вкладки встают
	 * прямо над ним и не перекрывают ни текст, ни сам ввод.
	 *
	 * @param screenHeight высота экрана чата в GUI-пикселях
	 * @return координата Y верхней границы полоски
	 */
	static int layoutYInChat(int screenHeight) {
		int y = screenHeight - CHAT_INPUT_HEIGHT - GAP_ABOVE - BAR_HEIGHT;
		return Math.max(y, 0);
	}

	/**
	 * Выбирает вкладку по координатам клика.
	 *
	 * <p>Клик по последней ячейке (🔧) не переключает вкладку, а открывает меню
	 * настроек: там вкладки добавляют, переименовывают и настраивают их
	 * категории, и это единственный вход в него.
	 *
	 * @return {@code true}, если клик попал в полоску и был обработан
	 */
	public static boolean selectAt(int mouseX, int mouseY) {
		List<ChatTab> tabs = barTabs();
		for (int i = 0; i < tabs.size(); i++) {
			if (hit(i, mouseX, mouseY)) {
				ChatRouter.getInstance().select(tabs.get(i));
				ChatTabsMod.logger().debug("Вкладка выбрана кликом: {}", tabs.get(i).name());
				return true;
			}
		}
		if (hit(tabs.size(), mouseX, mouseY)) {
			Minecraft minecraft = ChatRouter.minecraft();
			if (minecraft != null) {
				minecraft.setScreenAndShow(new TabSettingsScreen());
			}
			return true;
		}
		return false;
	}

	/** Попал ли клик в ячейку полоски с таким номером. */
	private static boolean hit(int slot, int mouseX, int mouseY) {
		return slot < MAX_TRACKED_CHANNELS
				&& mouseX >= BOX_X[slot] && mouseX < BOX_X[slot] + BOX_W[slot]
				&& mouseY >= BOX_Y[slot] && mouseY < BOX_Y[slot] + BOX_H[slot];
	}

	/**
	 * Прямоугольники ячеек последнего кадра — для отладки и проверок.
	 *
	 * <p>Последний элемент — ячейка настроек, у неё пятое число равно
	 * {@code -1}: вкладки с таким номером не существует, и проверка попаданий
	 * этим и отличается.
	 */
	public static List<int[]> layout() {
		List<int[]> boxes = new ArrayList<>();
		List<ChatTab> tabs = barTabs();
		for (int i = 0; i < tabs.size(); i++) {
			boxes.add(new int[] {BOX_X[i], BOX_Y[i], BOX_W[i], BOX_H[i], i});
		}
		boxes.add(new int[] {BOX_X[tabs.size()], BOX_Y[tabs.size()], BOX_W[tabs.size()],
				BOX_H[tabs.size()], -1});
		return boxes;
	}
}