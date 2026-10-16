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
		 * @param fade видимость чата 0..1 — на неё умножается альфа всех цветов,
		 *     чтобы вкладки гасли ровно вместе с чатом
		 */
		private static void draw(GuiGraphicsExtractor graphics, Font font, int y, float fade) {
			ChatRouter router = ChatRouter.getInstance();
			int x = MARGIN_X;

			// Рисуем только видимые вкладки: выключенная галочкой не должна
			// занимать место в полоске и попадать в переключение по кругу.
			ChatChannel[] channels = TabSettings.getInstance().visibleChannels();
			for (int i = 0; i < channels.length; i++) {
				ChatChannel channel = channels[i];
				boolean active = channel == router.activeTab();
				int unread = router.store().unreadCount(channel);

				String label = unread > 0
						? channel.tabLabel() + " " + unread
						: channel.tabLabel();

				int width = font.width(label) + 8;

				// Активная вкладка — светлее и непрозрачнее, остальные приглушены.
				graphics.fill(x, y, x + width, y + BAR_HEIGHT,
						withAlpha(active ? TAB_BG_ACTIVE : TAB_BG_INACTIVE, fade));

				// Цвет текста — ARGB, и text() берёт альфу из старшего байта.
				// У 0xFFFFFF альфа = 0, то есть текст полностью прозрачен: подписи
				// вкладок были не видны, хотя фон рисовался. Поэтому 0xFF обязателен.
				graphics.text(font, label, x + 4, y + 2,
						withAlpha(active ? TAB_TEXT_ACTIVE : TAB_TEXT_INACTIVE, fade), true);

				BOX_X[i] = x;
				BOX_Y[i] = y;
				BOX_W[i] = width;
				BOX_H[i] = BAR_HEIGHT;

				x += width + TAB_GAP;
			}
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

	/** Заполняет прямоугольники вкладок без отрисовки — для проверки клика. */
	private static void build(Font font, int y) {
		int x = MARGIN_X;
		ChatChannel[] channels = TabSettings.getInstance().visibleChannels();
		for (int i = 0; i < channels.length; i++) {
			int unread = ChatRouter.getInstance().store().unreadCount(channels[i]);
			String label = unread > 0 ? channels[i].tabLabel() + " " + unread : channels[i].tabLabel();

			BOX_X[i] = x;
			BOX_Y[i] = y;
			BOX_W[i] = font.width(label) + 8;
			BOX_H[i] = BAR_HEIGHT;

			x += BOX_W[i] + TAB_GAP;
		}
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
	 * @return {@code true}, если клик попал во вкладку и она была выбрана
	 */
	public static boolean selectAt(int mouseX, int mouseY) {
		ChatChannel[] channels = TabSettings.getInstance().visibleChannels();
		for (int i = 0; i < channels.length; i++) {
			if (mouseX >= BOX_X[i] && mouseX < BOX_X[i] + BOX_W[i]
					&& mouseY >= BOX_Y[i] && mouseY < BOX_Y[i] + BOX_H[i]) {
				ChatRouter.getInstance().select(channels[i]);
				ChatTabsMod.logger().debug("Вкладка выбрана кликом: {}", channels[i].displayName());
				return true;
			}
		}
		return false;
	}

	/** Прямоугольники вкладок последнего кадра — для отладки и тестов. */
	public static List<int[]> layout() {
		List<int[]> boxes = new ArrayList<>();
		ChatChannel[] channels = TabSettings.getInstance().visibleChannels();
		for (int i = 0; i < channels.length; i++) {
			boxes.add(new int[] {BOX_X[i], BOX_Y[i], BOX_W[i], BOX_H[i], channels[i].ordinal()});
		}
		return boxes;
	}
}