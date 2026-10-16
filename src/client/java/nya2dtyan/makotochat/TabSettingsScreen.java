package nya2dtyan.makotochat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.EnumSet;
import java.util.List;

/**
 * Меню настроек вкладок: список вкладок игрока и редактор одной вкладки.
 *
 * <p>Два режима в одном экране:
 *
 * <ul>
 *   <li><b>Список</b> — вкладки в порядке полоски. Слева ручка, за неё вкладку
 *       перетаскивают; клик по названию открывает редактор; справа глаз
 *       (показывать ли вкладку в полоске) и крестик (удалить). Внизу строка
 *       «+ Новая вкладка» — она и открывает выбор типов сообщений;</li>
 *   <li><b>Редактор</b> — имя вкладки и список типов сообщений с галочками.
 *       Для новой вкладки он пустой, и игрок сам решает, что в неё попадает.</li>
 * </ul>
 *
 * <p><b>Почему вкладка — это набор типов сообщений.</b> Раньше вкладка была
 * жёстко привязана к каналу, и «Общий» показывал всё без исключений. Теперь
 * достаточно снять галочку — и тикеты уйдут из «Общего», оставшись только там,
 * где эта категория отмечена. Особых случаев в коде не осталось: видимость
 * сообщения во вкладке — это пересечение двух наборов.
 *
 * <p>Геометрия вынесена в чистые функции ({@link #rowTop}, {@link #panelHeight},
 * {@link #slotAt}): ошибку раскладки не видно без запуска игры, а так её
 * проверяет стенд.
 */
public final class TabSettingsScreen extends Screen {

	/** Ширина окна в GUI-пикселях. */
	static final int PANEL_WIDTH = 340;

	/** Заголовок, строки, нижняя полоса и рамка. */
	static final int HEADER_HEIGHT = 22;
	static final int ROW_HEIGHT = 16;
	static final int ROW_GAP = 3;
	static final int FOOTER_HEIGHT = 22;
	static final int PADDING = 9;

	/** Сторона квадратного бокса с галочкой. */
	static final int BOX_SIZE = 10;

	/** Ширина служебных мест строки: ручка перетаскивания, глаз, крестик. */
	static final int HANDLE_WIDTH = 8;
	static final int BUTTON_WIDTH = 12;

	/**
	 * Цвета панели: ARGB, альфа обязательна — её читает {@code fill} и {@code text}.
	 *
	 * <p>Панель светлее и полупрозрачнее прежней: раньше это был почти чёрный
	 * прямоугольник, который закрывал мир и выглядел как отдельное окно. Сейчас
	 * она похожа на ванильные меню поверх игры — видно, что происходит за ней.
	 */
	private static final int COLOR_PANEL_BG = 0xA0545468;
	private static final int COLOR_PANEL_BORDER = 0x77E0E0F0;
	private static final int COLOR_TITLE = 0xFFFFD98A;
	private static final int COLOR_ROW_LABEL = 0xFFF4F4F8;
	private static final int COLOR_ROW_DIM = 0xFFC6C6D6;
	private static final int COLOR_ROW_HOVER = 0x26FFFFFF;
	private static final int COLOR_ROW_ACTIVE = 0x30FFD98A;
	private static final int COLOR_BOX_ON = 0xFF8ED96B;
	private static final int COLOR_BOX_OFF = 0xFF72728A;
	private static final int COLOR_BOX_MARK = 0xFF25252E;
	private static final int COLOR_FOOTER = 0xFFB4B4C4;
	private static final int COLOR_HANDLE = 0xFF9C9CB0;
	private static final int COLOR_FIELD = 0x70202028;
	private static final int COLOR_FIELD_BORDER = 0x55E0E0F0;

	/** Свой заголовок: поле {@code title} у {@link Screen} приватное. */
	private static final String TITLE = "🔧 Вкладки чата";

	/** Ячейки строки списка — по ним проверяется попадание клика. */
	static final int SLOT_HANDLE = 0;
	static final int SLOT_NAME = 1;
	static final int SLOT_EYE = 2;
	static final int SLOT_DELETE = 3;

	private final TabConfig config = TabConfig.getInstance();

	private int panelX;
	private int panelY;
	private int mouseX = -1;
	private int mouseY = -1;

	/** Строка, которую тянут, и куда она встанет, если отпустить кнопку. */
	private int dragFrom = -1;
	private int dragTo = -1;

	/** Строка, на которой зажата кнопка, но перетаскивание ещё не начато. */
	private int armedFrom = -1;

	/** Открытый редактор вкладки: {@code null} — режим списка. */
	private Edit editor;

	/** Запрошен ли у игры приём текста (нужен, пока открыт редактор имени). */
	private boolean textInputOn;

	public TabSettingsScreen() {
		super(Component.literal("Вкладки чата"));
	}

	/** Что редактируем: существующую вкладку или ещё не созданную. */
	private static final class Edit {

		/** Существующая вкладка или {@code null}, если вкладка ещё создаётся. */
		private final ChatTab target;

		private final StringBuilder name = new StringBuilder();

		private final EnumSet<MessageCategory> categories = EnumSet.noneOf(MessageCategory.class);

		private Edit(ChatTab target) {
			this.target = target;
			if (target != null) {
				name.append(target.name());
				categories.addAll(target.categories());
			} else {
				name.append("Вкладка ");
				name.append(TabConfig.getInstance().tabs().size() + 1);
			}
		}

		private boolean isNew() {
			return target == null;
		}
	}

	@Override
	protected void init() {
		panelX = panelX(width);
		panelY = panelY(height, rowCount());
	}

	// --- чистая геометрия: её проверяет стенд --------------------------------

	/** Левый край окна: по центру экрана. */
	static int panelX(int screenWidth) {
		return Math.max(PADDING, (screenWidth - PANEL_WIDTH) / 2);
	}

	/** Y верхней границы строки с таким номером. */
	static int rowTop(int panelTop, int index) {
		return panelTop + HEADER_HEIGHT + index * (ROW_HEIGHT + ROW_GAP);
	}

	/** Ширина мест под кнопки в нижней полосе редактора. */
	static final int BUTTON_SLOT = 60;

	/** Левый край кнопки «Готово» — она прижата к правому краю окна. */
	static int doneButtonLeft(int panelLeft) {
		return panelLeft + PANEL_WIDTH - PADDING - BUTTON_SLOT;
	}

	/** Левый край кнопки «Назад» — слева от «Готово». */
	static int backButtonLeft(int panelLeft) {
		return doneButtonLeft(panelLeft) - BUTTON_SLOT - 4;
	}

	/** Высота окна под такое число строк. */
	static int panelHeight(int rows) {
		return HEADER_HEIGHT + rows * (ROW_HEIGHT + ROW_GAP) + FOOTER_HEIGHT;
	}

	/** Y верхнего края окна: по центру экрана. */
	static int panelY(int screenHeight, int rows) {
		return Math.max(PADDING, (screenHeight - panelHeight(rows)) / 2);
	}

	/**
	 * В какую ячейку строки попадает координата X (от левого края окна).
	 *
	 * <p>Порядок проверок важен: глаз и крестик стоят у правого края и идут
	 * первыми, иначе название вкладки «съело» бы клики по ним.
	 */
	static int slotAt(int relX) {
		int deleteFrom = PANEL_WIDTH - PADDING - BUTTON_WIDTH;
		int eyeFrom = deleteFrom - BUTTON_WIDTH - 2;
		if (relX >= deleteFrom) {
			return SLOT_DELETE;
		}
		if (relX >= eyeFrom) {
			return SLOT_EYE;
		}
		if (relX < PADDING + HANDLE_WIDTH) {
			return SLOT_HANDLE;
		}
		return SLOT_NAME;
	}

	/** Сколько строк в списке: вкладки, «новая вкладка» и «сбросить». */
	int rowCount() {
		return config.tabs().size() + 2;
	}

	/** Номер строки «+ Новая вкладка». */
	int newRowIndex() {
		return config.tabs().size();
	}

	/** Номер строки «Сбросить настройки». */
	int resetRowIndex() {
		return config.tabs().size() + 1;
	}

	// --- отрисовка ----------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mx, int my, float delta) {
		super.extractRenderState(graphics, mx, my, delta);
		mouseX = mx;
		mouseY = my;
		panelX = panelX(width);
		panelY = panelY(height, rowCount());
		if (editor == null) {
			drawList(graphics);
		} else {
			drawEditor(graphics);
		}
	}

	private void drawList(GuiGraphicsExtractor graphics) {
		int panelH = panelHeight(rowCount());
		graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelH, COLOR_PANEL_BG);
		outline(graphics, panelX, panelY, PANEL_WIDTH, panelH);
		graphics.text(font, TITLE, panelX + (PANEL_WIDTH - font.width(TITLE)) / 2, panelY + 6,
				COLOR_TITLE, true);

		// Колонки строки: слева название, за ним типы сообщений, у правого края
		// глаз и крестик. Названию отдаём половину ширины — иначе длинное имя
		// наезжало на подпись типов, а та упиралась в кнопки.
		int textLeft = panelX + PADDING + HANDLE_WIDTH + 2;
		int buttonsLeft = panelX + PANEL_WIDTH - PADDING - BUTTON_WIDTH * 2 - 8;
		int nameWidth = (buttonsLeft - textLeft) / 2;
		int summaryLeft = textLeft + nameWidth + 8;

		List<ChatTab> tabs = config.tabs();
		for (int i = 0; i < tabs.size(); i++) {
			ChatTab tab = tabs.get(i);
			int top = rowTop(panelY, i);
			int textY = top + (ROW_HEIGHT - font.lineHeight) / 2;
			boolean active = tab == config.activeTab();

			// Активная вкладка подсвечена — видно, что сейчас открыто в чате.
			if (active) {
				graphics.fill(panelX + 1, top, panelX + PANEL_WIDTH - 1, top + ROW_HEIGHT,
						COLOR_ROW_ACTIVE);
			} else if (mouseY >= top && mouseY < top + ROW_HEIGHT) {
				graphics.fill(panelX + 1, top, panelX + PANEL_WIDTH - 1, top + ROW_HEIGHT,
						COLOR_ROW_HOVER);
			}

			// Ручка перетаскивания: за неё вкладку двигают.
			graphics.fill(panelX + PADDING, top + 4, panelX + PADDING + 2, top + ROW_HEIGHT - 4,
					COLOR_HANDLE);

			graphics.text(font, trim(tab.name(), textLeft, textLeft + nameWidth), textLeft, textY,
					active ? COLOR_TITLE : COLOR_ROW_LABEL, true);
			// Подпись типов показываем, только если она что-то добавляет: у вкладки
			// с одним типом сообщений она совпадает с названием, и строка выглядела
			// как задвоенный текст.
			String summary = summaryOf(tab);
			if (!summary.equalsIgnoreCase(tab.name())) {
				// На подсвеченной строке серый текст сливался с фоном, поэтому
				// там подпись рисуем тем же светлым тоном, что и название.
				graphics.text(font, trim(summary, summaryLeft, buttonsLeft - 6), summaryLeft, textY,
						active ? COLOR_ROW_LABEL : COLOR_ROW_DIM, false);
			}

			// Глаз: показывать вкладку в полоске или нет.
			graphics.text(font, tab.isVisible() ? "◉" : "○",
					panelX + PANEL_WIDTH - PADDING - BUTTON_WIDTH * 2 - 2, textY,
					tab.isVisible() ? COLOR_BOX_ON : COLOR_BOX_OFF, true);

			// Крестик у основной вкладки не рисуем: её удалить нельзя.
			if (!tab.isMain()) {
				graphics.text(font, "✕", panelX + PANEL_WIDTH - PADDING - BUTTON_WIDTH + 1, textY,
						COLOR_ROW_DIM, true);
			}
		}

		int newTop = rowTop(panelY, newRowIndex());
		graphics.text(font, "+ Новая вкладка", textLeft, newTop + (ROW_HEIGHT - font.lineHeight) / 2,
				COLOR_BOX_ON, true);
		int resetTop = rowTop(panelY, resetRowIndex());
		graphics.text(font, "Сбросить настройки", textLeft,
				resetTop + (ROW_HEIGHT - font.lineHeight) / 2, COLOR_ROW_DIM, true);

		// Тонкая линия отделяет действия и подсказку от списка вкладок — без неё
		// строки сливались в один столбик.
		int footerTop = panelY + panelH - FOOTER_HEIGHT;
		graphics.fill(panelX + PADDING, footerTop - 3, panelX + PANEL_WIDTH - PADDING,
				footerTop - 2, COLOR_PANEL_BORDER);
		// Подсказка короткая: подробности видны по самим элементам.
		graphics.text(font, "клик — настроить · ручка — перетащить", panelX + PADDING,
				footerTop + 7, COLOR_FOOTER, false);
	}

	private void drawEditor(GuiGraphicsExtractor graphics) {
		MessageCategory[] all = MessageCategory.values();
		int panelH = panelHeight(all.length + 1);
		graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelH, COLOR_PANEL_BG);
		outline(graphics, panelX, panelY, PANEL_WIDTH, panelH);

		String title = editor.isNew() ? "Новая вкладка" : "Вкладка «" + editor.target.name() + "»";
		graphics.text(font, title, panelX + (PANEL_WIDTH - font.width(title)) / 2, panelY + 6,
				COLOR_TITLE, true);

		// Имя вкладки: своё поле, курсор стоит в конце строки.
		int fieldTop = rowTop(panelY, 0);
		int textY = fieldTop + (ROW_HEIGHT - font.lineHeight) / 2;
		graphics.text(font, "Имя:", panelX + PADDING, textY, COLOR_ROW_DIM, false);
		int fieldX = panelX + PADDING + font.width("Имя:") + 6;
		int fieldRight = panelX + PANEL_WIDTH - PADDING;
		graphics.fill(fieldX, fieldTop + 1, fieldRight, fieldTop + ROW_HEIGHT - 1, COLOR_FIELD);
		// Рамка поля: без неё непонятно, что сюда можно печатать.
		graphics.fill(fieldX, fieldTop, fieldRight, fieldTop + 1, COLOR_FIELD_BORDER);
		graphics.fill(fieldX, fieldTop + ROW_HEIGHT - 1, fieldRight, fieldTop + ROW_HEIGHT,
				COLOR_FIELD_BORDER);
		String shownName = trim(editor.name.toString(), fieldX, fieldRight - 8);
		graphics.text(font, shownName, fieldX + 4, textY, COLOR_ROW_LABEL, true);
		int caretX = fieldX + 4 + font.width(shownName);
		graphics.fill(caretX + 1, fieldTop + 3, caretX + 2, fieldTop + ROW_HEIGHT - 3,
				COLOR_TITLE);

		// Типы сообщений с галочками — тот самый список для выбора.
		for (int i = 0; i < all.length; i++) {
			MessageCategory category = all[i];
			int top = rowTop(panelY, i + 1);
			boolean on = editor.categories.contains(category);
			drawCheckbox(graphics, panelX + PADDING, top + (ROW_HEIGHT - BOX_SIZE) / 2, on);
			graphics.text(font, category.displayName(), panelX + PADDING + BOX_SIZE + 8,
					top + (ROW_HEIGHT - font.lineHeight) / 2,
					on ? COLOR_ROW_LABEL : COLOR_ROW_DIM, true);
		}

		// Из редактора должно быть два выхода: кнопкой и по Esc. Раньше был
		// только «Готово», а Esc не обрабатывался — выйти было нечем.
		int buttonTop = panelY + panelH - FOOTER_HEIGHT + 6;
		graphics.text(font, "[Назад]", backButtonLeft(panelX), buttonTop, COLOR_ROW_LABEL, true);
		graphics.text(font, "[Готово]", doneButtonLeft(panelX), buttonTop, COLOR_BOX_ON, true);
	}

	/** Квадрат с галочкой: зелёный и с отметкой — включено. */
	private void drawCheckbox(GuiGraphicsExtractor graphics, int x, int y, boolean on) {
		graphics.fill(x, y, x + BOX_SIZE, y + BOX_SIZE, on ? COLOR_BOX_ON : COLOR_BOX_OFF);
		if (on) {
			graphics.fill(x + 2, y + 2, x + BOX_SIZE - 2, y + BOX_SIZE - 2, COLOR_BOX_MARK);
		}
	}

	private void outline(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
		graphics.fill(x, y, x + w, y + 1, COLOR_PANEL_BORDER);
		graphics.fill(x, y + h - 1, x + w, y + h, COLOR_PANEL_BORDER);
		graphics.fill(x, y, x + 1, y + h, COLOR_PANEL_BORDER);
		graphics.fill(x + w - 1, y, x + w, y + h, COLOR_PANEL_BORDER);
	}

	/** Подпись типов сообщений вкладки для строки списка. */
	private static String summaryOf(ChatTab tab) {
		if (tab.categoryCount() == MessageCategory.values().length) {
			return "все сообщения";
		}
		if (tab.categoryCount() == 0) {
			return "пусто";
		}
		StringBuilder out = new StringBuilder();
		for (MessageCategory category : MessageCategory.values()) {
			if (tab.has(category)) {
				if (out.length() > 0) {
					out.append(", ");
				}
				out.append(category.shortLabel());
			}
		}
		return out.toString();
	}

	/** Обрезает подпись до ширины, которая влезает в отведённое место. */
	private String trim(String text, int fromX, int toX) {
		int room = toX - fromX;
		if (room <= 0) {
			return "";
		}
		if (font.width(text) <= room) {
			return text;
		}
		String cut = text;
		while (cut.length() > 1 && font.width(cut + "…") > room) {
			cut = cut.substring(0, cut.length() - 1);
		}
		return cut + "…";
	}

	// --- ввод ---------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int x = (int) event.x();
		int y = (int) event.y();
		return editor == null ? clickInList(x, y) : clickInEditor(x, y);
	}

	private boolean clickInList(int x, int y) {
		if (x < panelX || x > panelX + PANEL_WIDTH) {
			return false;
		}
		int row = (y - panelY - HEADER_HEIGHT) / (ROW_HEIGHT + ROW_GAP);
		int top = rowTop(panelY, row);
		if (row < 0 || y < top || y >= top + ROW_HEIGHT) {
			return false;
		}
		if (row == newRowIndex()) {
			openEditor(null);
			return true;
		}
		if (row == resetRowIndex()) {
			config.resetToDefaults();
			afterChange();
			return true;
		}
		List<ChatTab> tabs = config.tabs();
		if (row >= tabs.size()) {
			return false;
		}
		ChatTab tab = tabs.get(row);

		switch (slotAt(x - panelX)) {
			case SLOT_DELETE -> {
				// У основной вкладки крестика нет — и удалить её нельзя.
				if (config.removeTab(tab)) {
					ChatRouter.getInstance().store().forget(tab);
					afterChange();
				}
				return true;
			}
			case SLOT_EYE -> {
				tab.setVisible(!tab.isVisible());
				config.save();
				afterChange();
				return true;
			}
			case SLOT_HANDLE -> {
				armedFrom = row;
				dragFrom = -1;
				dragTo = -1;
				return true;
			}
			default -> {
				openEditor(tab);
				return true;
			}
		}
	}

	private boolean clickInEditor(int x, int y) {
		MessageCategory[] all = MessageCategory.values();
		int panelH = panelHeight(all.length + 1);

		// Нижняя полоса: «Назад» и «Готово». Обе обязаны работать — выход из
		// редактора не должен зависеть от одной клавиши.
		if (y >= panelY + panelH - FOOTER_HEIGHT) {
			if (x >= doneButtonLeft(panelX)) {
				applyEditor();
				return true;
			}
			if (x >= backButtonLeft(panelX)) {
				closeEditor();
				return true;
			}
			return false;
		}
		int row = (y - panelY - HEADER_HEIGHT) / (ROW_HEIGHT + ROW_GAP);
		int top = rowTop(panelY, row);
		// Строка 0 — имя вкладки, дальше типы сообщений.
		if (row < 1 || row > all.length || y < top || y >= top + ROW_HEIGHT) {
			return false;
		}
		MessageCategory category = all[row - 1];
		// remove возвращает false, если галочки не было, — тогда её ставим.
		if (!editor.categories.remove(category)) {
			editor.categories.add(category);
		}
		return true;
	}

	private void openEditor(ChatTab tab) {
		editor = new Edit(tab);
		dragFrom = -1;
		armedFrom = -1;
		// Имя набирается с клавиатуры, поэтому объявляем игре, что здесь есть
		// поле ввода: иначе символы до экрана не доходят (см. textInputFocus).
		textInputFocus(true);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (editor != null || armedFrom < 0) {
			return super.mouseDragged(event, dragX, dragY);
		}
		if (dragFrom < 0) {
			dragFrom = armedFrom;
			dragTo = armedFrom;
		}
		mouseX = (int) event.x();
		mouseY = (int) event.y();
		int over = (mouseY - panelY - HEADER_HEIGHT) / (ROW_HEIGHT + ROW_GAP);
		int limit = config.tabs().size() - 1;
		dragTo = over < 0 || over > limit ? dragFrom : over;
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		int armed = armedFrom;
		armedFrom = -1;
		if (editor != null || armed < 0) {
			return super.mouseReleased(event);
		}
		if (dragFrom < 0) {
			return true;
		}
		config.move(dragFrom, dragTo);
		dragFrom = -1;
		dragTo = -1;
		afterChange();
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (editor != null && event.isAllowedChatCharacter()) {
			// Курсор всегда в конце: этого достаточно для имени вкладки, а
			// полноценный текстовый редактор здесь был бы лишним весом.
			editor.name.append(event.codepointAsString());
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (editor != null) {
			int key = event.key();
			if (key == InputConstants.KEY_ESCAPE) {
				// Esc закрывает редактор, а не экран: игрок возвращается к списку
				// вкладок и может выйти оттуда. Раньше здесь не было обработки, а
				// ванильное закрытие по Esc запрещено (см. shouldCloseOnEsc), и
				// из редактора было не выйти вообще — ни Esc, ни кнопкой.
				closeEditor();
				return true;
			}
			if (key == InputConstants.KEY_BACKSPACE) {
				if (!editor.name.isEmpty()) {
					editor.name.deleteCharAt(editor.name.length() - 1);
				}
				return true;
			}
			if (key == InputConstants.KEY_RETURN) {
				applyEditor();
				return true;
			}
		}
		return super.keyPressed(event);
	}

	/**
	 * Включает или выключает приём текста для этого экрана.
	 *
	 * <p>В 26.3 игра <b>останавливает</b> ввод текста при каждой смене экрана
	 * ({@code Gui.setScreen} вызывает {@code stopTextInput}), и включает его
	 * только тот, кто объявил, что в нём есть поле ввода. Без этого SDL не шлёт
	 * символы, {@code charTyped} не вызывается и набранный текст никуда не
	 * попадает — именно поэтому имя вкладки не набиралось.
	 *
	 * <p>Состояние помним у себя: повторный запрос — лишняя работа, а главное,
	 * стенду нужно видеть, что экран действительно его сделал.
	 */
	private void textInputFocus(boolean on) {
		if (textInputOn == on) {
			return;
		}
		textInputOn = on;
		Minecraft minecraft = ChatRouter.minecraft();
		if (minecraft != null) {
			minecraft.onTextInputFocusChange(this, on);
		}
	}

	/** Закрывает редактор, возвращая ввод текста игре. */
	private void closeEditor() {
		editor = null;
		textInputFocus(false);
	}

	/** Применяет редактор: создаёт вкладку или сохраняет правки существующей. */
	private void applyEditor() {
		if (editor == null) {
			return;
		}
		String name = editor.name.toString().trim();
		if (name.isEmpty()) {
			// Без имени вкладку не отличить от соседней, поэтому пустое не сохраняем.
			return;
		}
		if (editor.isNew()) {
			ChatTab created = config.addTab(name, editor.categories);
			if (created == null) {
				ChatTabsMod.logger().warn("Больше {} вкладок не завести", TabConfig.MAX_TABS);
				return;
			}
		} else {
			editor.target.setName(name);
			for (MessageCategory category : MessageCategory.values()) {
				editor.target.setCategory(category, editor.categories.contains(category));
			}
			config.save();
		}
		closeEditor();
		afterChange();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		// В редакторе Esc закрывает редактор, а не всё меню: иначе набранное имя
		// и отмеченные типы терялись бы целиком. Сам Esc обрабатывается в
		// keyPressed — ванильный путь сюда не доходит.
		return editor == null;
	}

	@Override
	public void onClose() {
		if (editor != null) {
			closeEditor();
			return;
		}
		super.onClose();
	}

	/**
	 * Экран заменяют другим: ввод текста за нами больше не числится.
	 *
	 * <p>Без этого игра осталась бы с включённым вводом после нашего экрана, и
	 * клавиатура в другом меню вела бы себя странно.
	 */
	@Override
	public void removed() {
		super.removed();
		textInputFocus(false);
	}

	/** Пересобирает чат: изменения вкладок сразу видно в самом чате. */
	private void afterChange() {
		ChatTabFilter.getInstance().rebuild();
	}
}