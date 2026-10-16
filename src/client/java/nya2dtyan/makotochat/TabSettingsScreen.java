package nya2dtyan.makotochat;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Меню настроек вкладок: список строк «название слева, бокс справа».
 *
 * <p>Открывается кликом по вкладке 🔧. Экран собственный, а не ванильный
 * {@code OptionsSubScreen}: там строки стандартные, а здесь каждая строка —
 * наш канал чата, и клик по ней должен гасить и зажигать конкретную вкладку.
 *
 * <p><b>Список рисуется вручную</b>, потому что ванильный {@code Button} не
 * умеет состояние «включено/выключено» без своей картинки: пришлось бы
 * рисовать текстуру галочки самим. Строка — это рамка {@link #fill} плюс
 * подпись шрифтом, а попадание клика считается по тем же координатам.
 *
 * <p>Отдельная функция {@link #layout(int, int)} считает прямоугольники без
 * доступа к GUI, чтобы геометрию можно было проверить тестом.
 */
public final class TabSettingsScreen extends Screen {

	/** Ширина окна в GUI-пикселях. */
	private static final int PANEL_WIDTH = 200;

	/** Заголовок + список вкладок + рамка сверху и снизу. */
	private static final int HEADER_HEIGHT = 20;
	private static final int ROW_HEIGHT = 14;
	private static final int ROW_GAP = 2;
	private static final int FOOTER_HEIGHT = 16;
	private static final int PADDING = 6;

	/** Цвета: ARGB, альфа обязательна — её читает {@code fill} и {@code text}. */
	private static final int COLOR_PANEL_BG = 0xC8101010;
	private static final int COLOR_PANEL_BORDER = 0xFF6E6E6E;
	private static final int COLOR_TITLE = 0xFFFFD24A;
	private static final int COLOR_ROW_LABEL = 0xFFE0E0E0;
	private static final int COLOR_ROW_HOVER = 0x40FFFFFF;
	private static final int COLOR_BOX_ON = 0xFF7BD44A;
	private static final int COLOR_BOX_OFF = 0xFF555555;
	private static final int COLOR_BOX_MARK = 0xFF101010;
	private static final int COLOR_FOOTER = 0xFF9A9A9A;
	private static final int COLOR_HANDLE = 0xFF8A8A8A;
	private static final int COLOR_DRAG_TARGET = 0x60FFD24A;

	/** Сторона квадратного бокса с галочкой. */
	private static final int BOX_SIZE = 9;

	/** Ширина «ручки» перетаскивания слева в каждой строке. */
	private static final int HANDLE_WIDTH = 8;

	/** Свой заголовок: поле {@code title} у {@link Screen} приватное. */
	private static final String TITLE = "🔧 Настройки вкладок";

	private final TabSettings settings = TabSettings.getInstance();

	private int panelX;
	private int panelY;
	private int mouseX = -1;
	private int mouseY = -1;

	/**
	* Индекс строки, которую тянут, и {@code -1}, когда не тянут.
	*
	* <p>Отдельный от «наведённой» строки: наведение меняется сама, а перетаскивание
	* живёт от нажатия до отпускания, и пока её тянут — строка едет за курсором.
	*/
	private int dragFrom = -1;
	
	/** Куда сейчас встанет перетаскиваемая строка, если отпустить кнопку. */
	private int dragTo = -1;
	
	/**
	* Строка, на которой зажата кнопка, но ещё не начато перетаскивание.
	*
	* <p>Нужна, чтобы отличить клик от перетаскивания: пока мышь не сдвинулась,
	* нажатие считается обычным кликом по названию и переключает вкладку, а первое
	* же смещение превращает его в перетаскивание.
	*/
	private int armedFrom = -1;
	
	public TabSettingsScreen() {
	super(Component.literal("Настройки вкладок"));
	}

	@Override
	protected void init() {
		layout(width, height);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mx, int my, float delta) {
		super.extractRenderState(graphics, mx, my, delta);
		mouseX = mx;
		mouseY = my;
		drawPanel(graphics);
	}

	private void drawPanel(GuiGraphicsExtractor graphics) {
		int fontHeight = font.lineHeight;
		int panelH = layoutHeight();

		// Фон и рамка: как у ванильных меню, чтобы не выбиваться из интерфейса.
		graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelH, COLOR_PANEL_BG);
		outline(graphics, panelX, panelY, PANEL_WIDTH, panelH);

		// Заголовок по центру — так же, как в меню сервера на скриншоте.
		graphics.text(font, TITLE, panelX + (PANEL_WIDTH - font.width(TITLE)) / 2,
				panelY + 5, COLOR_TITLE, true);

		// Строки: слева ручка перетаскивания, название, справа бокс с галочкой.
		ChatChannel[] rows = rows();
		for (int i = 0; i < rows.length; i++) {
			// Во время перетаскивания порядок рисуется «как будет», поэтому
			// перетаскиваемая строка уже стоит на своём новом месте.
			ChatChannel channel = rows[i];
			boolean dragging = dragFrom >= 0 && i == dragFrom;
			int rowY = rowY(i);
			boolean configurable = settings.isConfigurable(channel);
			boolean enabled = settings.isEnabled(channel);

			// Место, куда встанет строка: жёлтая полоса на всю ширину.
			if (dragFrom >= 0 && i == dragTo && dragTo != dragFrom) {
				graphics.fill(panelX + 1, rowY, panelX + PANEL_WIDTH - 1, rowY + ROW_HEIGHT,
						COLOR_DRAG_TARGET);
			}

			if (dragging) {
				// Строка едет за курсором и рисуется последней — поверх остальных.
				continue;
			}

			if (hovered(i, rows.length)) {
				graphics.fill(panelX + 1, rowY, panelX + PANEL_WIDTH - 1, rowY + ROW_HEIGHT,
						COLOR_ROW_HOVER);
			}

			drawRow(graphics, channel, rowY, configurable, enabled);
		}

		// Перетаскиваемая строка — поверх всех, с рамкой, чтобы её было видно.
		if (dragFrom >= 0) {
			int ghostY = mouseY - ROW_HEIGHT / 2;
			graphics.fill(panelX + 1, ghostY, panelX + PANEL_WIDTH - 1, ghostY + ROW_HEIGHT,
					COLOR_ROW_HOVER);
			outline(graphics, panelX + 1, ghostY, PANEL_WIDTH - 2, ROW_HEIGHT);
			drawRow(graphics, rows[dragFrom], ghostY,
					settings.isConfigurable(rows[dragFrom]), settings.isEnabled(rows[dragFrom]));
		}

		String footer = "Клик — вкл/выкл.  Тяни строку — порядок.  Esc — закрыть";
		graphics.text(font, footer,
				panelX + (PANEL_WIDTH - font.width(footer)) / 2,
				panelY + panelH - FOOTER_HEIGHT, COLOR_FOOTER, true);
	}

	/**
	 * Нажатие: клик по боксу с галочкой переключает вкладку, клик по строке — начало перетаскивания.
	 *
	 * <p>Два действия разведены по месту клика, а не по кнопке мыши: привычный
	 * клик по названию включает-выключает, а тянуть вкладку удобнее всего за
	 * левый край строки с «ручкой». Клик по самой ручке тоже начинает перетаскивание —
	 * иначе ручка была бы значком, который ничего не делает.
	 *
	 * <p>Перетаскивание фиксируется только на первом движении мыши (см.
	 * {@link #mouseDragged}): без этого обычный клик по строке без смещения
	 * считался бы перетаскиванием и гасил бы вкладку вместо переключения.
	 */
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		ChatChannel[] rows = rows();
		int index = indexAt((int) event.y(), rows.length);
		if (index < 0) {
			return super.mouseClicked(event, doubleClick);
		}
		mouseX = (int) event.x();
		mouseY = (int) event.y();

		int boxX = panelX + PANEL_WIDTH - PADDING - BOX_SIZE;
		if (event.x() >= boxX - 2) {
			toggle(index, rows);
			return true;
		}

		// 🔧 всегда в конце и не двигается: нажатие на неё ничего не делает.
		armedFrom = rows[index] == ChatChannel.SETTINGS ? -1 : index;
		dragFrom = -1;
		dragTo = -1;
		return true;
	}

	/**
	 * Движение с зажатой кнопкой: перетаскиваемая строка едет за курсором.
	 *
	 * <p>Строка взводится в {@link #armedFrom} на нажатии, а в перетаскивание
	 * входит только здесь — по первому же смещению. {@link TabSettings#moveChannel(int, int)}
	 * вызывается на отпускании, а не здесь: иначе список дёргался бы под курсором.
	 */
	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (armedFrom < 0) {
			return super.mouseDragged(event, dragX, dragY);
		}
		if (dragFrom < 0) {
			dragFrom = armedFrom;
			dragTo = armedFrom;
		}
		mouseX = (int) event.x();
		mouseY = (int) event.y();
		int count = rows().length;
		int over = indexAt(mouseY, count);
		dragTo = over < 0 ? dragFrom : over;
		return true;
	}

	/** Отпускание: роняет строку на новое место, если её тянули. */
	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		int armed = armedFrom;
		armedFrom = -1;
		if (armed < 0) {
			return super.mouseReleased(event);
		}
		if (dragFrom < 0) {
			// Кнопку нажали и отпустили без смещения — это обычный клик по строке.
			toggle(armed, rows());
			return true;
		}
		boolean changed = settings.moveChannel(dragFrom, dragTo);
		dragFrom = -1;
		dragTo = -1;
		if (changed) {
			// Полоску вкладок пересобирать не нужно: она читает порядок из
			// настроек при каждом отрисовке, поэтому новый порядок виден сразу.
			ChatTabFilter.getInstance().rebuild();
		}
		return true;
	}

	/**
	 * Индекс строки под координатой {@code y} или {@code -1}, если мимо.
	 *
	 * <p>Строки идут сверху вниз в том же порядке, в каком рисуются, поэтому
	 * попадание считается от верха списка, а не от курсора над названием.
	 */
	private int indexAt(int y, int rowCount) {
		int listTop = panelY + HEADER_HEIGHT;
		int listHeight = rowCount * (ROW_HEIGHT + ROW_GAP);
		if (y < listTop || y >= listTop + listHeight) {
			return -1;
		}
		// Шаг по ROW_GAP такой же, как в rowY: иначе попадание в строки
		// копилось бы на полпикселя и кликал бы не по той вкладке.
		return (y - listTop) / (ROW_HEIGHT + ROW_GAP);
	}

	/**
	 * Рисует одну строку меню: ручка перетаскивания, название, бокс с галочкой.
	 *
	 * @param y верх строки
	 */
	private void drawRow(GuiGraphicsExtractor graphics, ChatChannel channel, int y,
			boolean configurable, boolean enabled) {
		int labelY = y + (ROW_HEIGHT - font.lineHeight) / 2 + 1;
		int boxX = panelX + PANEL_WIDTH - PADDING - BOX_SIZE;
		int boxY = y + (ROW_HEIGHT - BOX_SIZE) / 2;

		drawHandle(graphics, y, channel != ChatChannel.SETTINGS);

		graphics.text(font, channel.displayName(), panelX + PADDING + HANDLE_WIDTH, labelY,
				COLOR_ROW_LABEL, true);

		// 🔧 всегда включена: показываем её, но галочку не даём снять.
		if (configurable) {
			drawBox(graphics, boxX, boxY, enabled);
		} else {
			graphics.fill(boxX, boxY, boxX + BOX_SIZE, boxY + BOX_SIZE, COLOR_BOX_ON);
			graphics.text(font, "всегда", boxX - font.width("всегда") - 4,
					labelY, COLOR_FOOTER, true);
		}
	}

	/**
	 * Ручка перетаскивания слева: две точечные полоски, как у ползунка.
	 *
	 * <p>У служебной 🔧 ручки нет: её нельзя двигать, и показывать значку,
	 * который ничего не делает, незачем.
	 */
	private void drawHandle(GuiGraphicsExtractor graphics, int rowY, boolean movable) {
		if (!movable) {
			return;
		}
		int x = panelX + PADDING;
		int top = rowY + 3;
		for (int i = 0; i < 3; i++) {
			graphics.fill(x, top + i * 3, x + 2, top + i * 3 + 1, COLOR_HANDLE);
			graphics.fill(x + 3, top + i * 3, x + 5, top + i * 3 + 1, COLOR_HANDLE);
		}
	}

	/** Рамка из полосок в один пиксель. */
	private void outline(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
		graphics.fill(x, y, x + w, y + 1, COLOR_PANEL_BORDER);
		graphics.fill(x, y + h - 1, x + w, y + h, COLOR_PANEL_BORDER);
		graphics.fill(x, y, x + 1, y + h, COLOR_PANEL_BORDER);
		graphics.fill(x + w - 1, y, x + w, y + h, COLOR_PANEL_BORDER);
	}

	/**
	 * Бокс с галочкой.
	 *
	 * <p>Галочку рисуем двумя полосками, а не символом шрифта: в Minecraft нет
	 * галочки в основном начертании, а свой глиф в атласе шрифта есть не всегда.
	 */
	private void drawBox(GuiGraphicsExtractor graphics, int x, int y, boolean enabled) {
		graphics.fill(x, y, x + BOX_SIZE, y + BOX_SIZE, enabled ? COLOR_BOX_ON : COLOR_BOX_OFF);
		outline(graphics, x, y, BOX_SIZE, BOX_SIZE);
		if (enabled) {
			// Две диагональные полоски — короткая и длинная часть галочки.
			graphics.fill(x + 2, y + 5, x + 4, y + 6, COLOR_BOX_MARK);
			graphics.fill(x + 3, y + 4, x + 5, y + 6, COLOR_BOX_MARK);
			graphics.fill(x + 4, y + 3, x + 7, y + 4, COLOR_BOX_MARK);
			graphics.fill(x + 5, y + 2, x + 8, y + 3, COLOR_BOX_MARK);
		}
	}

	private void toggle(int index, ChatChannel[] rows) {
		ChatChannel channel = rows[index];
		if (!settings.isConfigurable(channel)) {
			return;
		}
		boolean nowEnabled = settings.toggle(channel);
		ChatTabsMod.logger().debug("Вкладка {} {}", channel.displayName(),
				nowEnabled ? "включена" : "выключена");
		// Чат могло попасть в только что погашенную вкладку — активной должна
		// остаться видимая, иначе игрок останется в несуществующей вкладке.
		if (!settings.isEnabled(ChatRouter.getInstance().activeTab())) {
			ChatChannel[] visible = settings.visibleChannels();
			if (visible.length > 0) {
				ChatRouter.getInstance().select(visible[0]);
			}
		}
		// Пересобираем чат: список видимых строк зависит от активной вкладки.
		ChatTabFilter.getInstance().rebuild();
	}

	/**
	 * Каналы в порядке показа в меню — тот же, что и в полоске вкладок.
	 *
	 * <p>Список, а не {@link ChatChannel#values()}: иначе перетаскивание было бы
	 * без толку — на экране вкладки шли бы в порядке объявления констант.
	 * Выключенные вкладки тоже в списке: их надо куда-то возвращать.
	 */
	static ChatChannel[] rows() {
		return TabSettings.getInstance().orderedChannels();
	}

	private boolean hovered(int index, int count) {
		int rowY = rowY(index);
		return mouseX >= panelX + 1 && mouseX < panelX + PANEL_WIDTH - 1
				&& mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
	}

	private int rowY(int index) {
		return panelY + HEADER_HEIGHT + index * (ROW_HEIGHT + ROW_GAP);
	}

	private static int layoutHeight() {
		return HEADER_HEIGHT + rows().length * (ROW_HEIGHT + ROW_GAP) + FOOTER_HEIGHT;
	}

	/**
	 * Положение окна — по центру экрана.
	 *
	 * <p>Вынесено отдельно и без зависимостей от GUI: иначе геометрию было бы
	 * нечем проверить без запуска игры.
	 */
	void layout(int screenWidth, int screenHeight) {
		panelX = (screenWidth - PANEL_WIDTH) / 2;
		panelY = Math.max(4, (screenHeight - layoutHeight()) / 2);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}