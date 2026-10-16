import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/**
 * Проверка меню настроек на собранном jar: вход в вкладку, набор имени и выход
 * из редактора.
 *
 * <p>Случаи, из-за которых написана проверка:
 * <ul>
 *   <li>в редакторе вкладки имя не набиралось: в 26.3 игра останавливает ввод
 *       текста при каждой смене экрана, и включает его только тот, кто объявил,
 *       что у него есть поле ввода. Проверяется, что экран это делает;</li>
 *   <li>из редактора было не выйти: Esc не обрабатывался, а ванильное закрытие
 *       запрещено, пока редактор открыт. Проверяются оба выхода — клавиша и
 *       кнопка «Назад»;</li>
 *   <li>набранное имя и отмеченные типы должны попадать во вкладку по «Готово».</li>
 * </ul>
 */
public class VerifySettingsScreen {

	private static final int WIDTH = 400;
	private static final int HEIGHT = 300;

	private static int pass;
	private static int fail;

	public static void main(String[] args) throws Exception {
		URL modJar = new java.io.File(args[0]).toURI().toURL();
		try (URLClassLoader loader = new URLClassLoader(new URL[] {modJar},
				VerifySettingsScreen.class.getClassLoader())) {

			Class<?> screenClass = loader.loadClass("nya2dtyan.makotochat.TabSettingsScreen");
			Class<?> base = Class.forName("net.minecraft.client.gui.screens.Screen");
			Class<?> configClass = loader.loadClass("nya2dtyan.makotochat.TabConfig");
			Class<?> tabClass = loader.loadClass("nya2dtyan.makotochat.ChatTab");
			Class<?> categoryClass = loader.loadClass("nya2dtyan.makotochat.MessageCategory");

			Object config = configClass.getMethod("getInstance").invoke(null);
			configClass.getMethod("resetToDefaults").invoke(config);

			// Экран создаём без вызова конструктора: ванильный Screen в
			// конструкторе берёт шрифт у клиента, которого вне игры нет. Поля,
			// которые инициализирует конструктор, выставляем руками.
			Object screen = allocate(screenClass);
			setField(screenClass, screen, "config", config);

			// Раскладку задаём теми же чистыми функциями, что и сам экран, —
			// проверяются настоящие координаты.
			int listRows = (int) method(screenClass, "rowCount").invoke(screen);
			int panelX = (int) method(screenClass, "panelX", int.class).invoke(null, WIDTH);
			int panelY = (int) method(screenClass, "panelY", int.class, int.class).invoke(null, HEIGHT, listRows);
			setField(screenClass, screen, "panelX", panelX);
			setField(screenClass, screen, "panelY", panelY);
			int firstRow = (int) method(screenClass, "rowTop", int.class, int.class).invoke(null, panelY, 0);

			// 1. Клик по названию вкладки открывает редактор.
			click(screen, base, panelX + 40, firstRow + 5);
			check("клик по названию вкладки открывает редактор", editor(screenClass, screen) != null);
			check("экран объявляет, что в нём есть поле ввода",
					textInputRequested(screenClass, screen));

			// 2. Имя набирается с клавиатуры.
			String typed = nameOf(screenClass, screen);
			type(screen, base, 'Я');
			check("набранный символ попадает в имя",
					nameOf(screenClass, screen).equals(typed + "Я"));

			// 3. Backspace стирает последний символ.
			key(screen, base, "KEY_BACKSPACE");
			check("Backspace стирает символ", nameOf(screenClass, screen).equals(typed));

			// 4. Esc возвращает к списку, а не закрывает экран целиком.
			key(screen, base, "KEY_ESCAPE");
			check("Esc выходит из редактора к списку", editor(screenClass, screen) == null);
			check("после выхода из редактора ввод текста отпущен",
					!textInputRequested(screenClass, screen));
			check("из списка Esc закрывает экран",
					(boolean) base.getMethod("shouldCloseOnEsc").invoke(screen));

			// 5. Второй выход из редактора — кнопка «Назад».
			click(screen, base, panelX + 40, firstRow + 5);
			check("редактор открывается повторно", editor(screenClass, screen) != null);
			int editorRows = categoryClass.getEnumConstants().length + 1;
			int editorPanelY = (int) method(screenClass, "panelY", int.class, int.class)
					.invoke(null, HEIGHT, listRows);
			int editorHeight = (int) method(screenClass, "panelHeight", int.class).invoke(null, editorRows);
			int buttonY = editorPanelY + editorHeight - 8;
			int backX = (int) method(screenClass, "backButtonLeft", int.class).invoke(null, panelX);
			int doneX = (int) method(screenClass, "doneButtonLeft", int.class).invoke(null, panelX);
			int panelWidth = (int) field(screenClass, "PANEL_WIDTH").get(null);
			check("кнопка «Назад» стоит слева от «Готово»", backX < doneX);
			check("обе кнопки внутри окна",
					backX > panelX && doneX + 60 <= panelX + panelWidth);
			click(screen, base, backX + 5, buttonY);
			check("клик по «Назад» выходит из редактора", editor(screenClass, screen) == null);

			// 6. Создание вкладки: плюс, имя, тип сообщения, «Готово».
			List<?> before = (List<?>) configClass.getMethod("tabs").invoke(config);
			int sizeBefore = before.size();
			int newRow = (int) method(screenClass, "newRowIndex").invoke(screen);
			int newRowTop = (int) method(screenClass, "rowTop", int.class, int.class)
					.invoke(null, panelY, newRow);
			click(screen, base, panelX + 40, newRowTop + 5);
			check("строка «Новая вкладка» открывает редактор", editor(screenClass, screen) != null);

			type(screen, base, '!');
			// Первая строка типов сообщений — она же первая категория перечисления.
			Object firstCategory = categoryClass.getEnumConstants()[0];
			int categoryRowTop = (int) method(screenClass, "rowTop", int.class, int.class)
					.invoke(null, editorPanelY, 1);
			click(screen, base, panelX + 40, categoryRowTop + 5);
			check("галочка типа сообщения отмечается", draftHasCategory(screenClass, screen, firstCategory));

			click(screen, base, doneX + 5, buttonY);
			check("«Готово» закрывает редактор", editor(screenClass, screen) == null);

			List<?> after = (List<?>) configClass.getMethod("tabs").invoke(config);
			check("вкладка создана", after.size() == sizeBefore + 1);

			Object created = after.get(after.size() - 1);
			String createdName = (String) tabClass.getMethod("name").invoke(created);
			check("набранный символ попал в название", createdName.endsWith("!"));
			check("выбранный тип сообщения попал во вкладку",
					(boolean) tabClass.getMethod("has", categoryClass).invoke(created, firstCategory));

			System.out.println();
			System.out.println("ИТОГО: успешно " + pass + ", провалено " + fail);
			if (fail > 0) {
				System.exit(1);
			}
		}
	}

	/**
	 * Ищет метод, включая пакетные: экран объявляет их без модификатора доступа,
	 * и обычный {@code getMethod} их не находит.
	 */
	private static Method method(Class<?> owner, String name, Class<?>... params) throws Exception {
		Method found = owner.getDeclaredMethod(name, params);
		found.setAccessible(true);
		return found;
	}

	/**
	 * Создаёт объект без вызова конструктора.
	 *
	 * <p>Так делают сериализаторы: конструктор ванильного экрана требует
	 * клиента, а проверить обработчики экрана нужно.
	 */
	private static Object allocate(Class<?> type) throws Exception {
		Class<?> factoryClass = Class.forName("sun.reflect.ReflectionFactory");
		Object factory = factoryClass.getMethod("getReflectionFactory").invoke(null);
		Constructor<?> objectConstructor = Object.class.getDeclaredConstructor();
		Constructor<?> constructor = (Constructor<?>) factoryClass
				.getMethod("newConstructorForSerialization", Class.class, Constructor.class)
				.invoke(factory, type, objectConstructor);
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	private static void setField(Class<?> owner, Object target, String name, Object value)
			throws Exception {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static void setField(Class<?> owner, Object target, String name, int value)
			throws Exception {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.setInt(target, value);
	}

	/** Поле, доступное для чтения: экран объявляет их пакетно. */
	private static Field field(Class<?> owner, String name) throws Exception {
		Field found = owner.getDeclaredField(name);
		found.setAccessible(true);
		return found;
	}

	/** Считается ли, что экран запросил ввод текста: счётчик вызовов в самом экране. */
	private static boolean textInputRequested(Class<?> screenClass, Object screen) throws Exception {
		return (boolean) field(screenClass, "textInputOn").get(screen);
	}

	private static Object editor(Class<?> screenClass, Object screen) throws Exception {
		return field(screenClass, "editor").get(screen);
	}

	private static String nameOf(Class<?> screenClass, Object screen) throws Exception {
		Object edit = editor(screenClass, screen);
		if (edit == null) {
			throw new IllegalStateException("редактор не открыт");
		}
		return field(edit.getClass(), "name").get(edit).toString();
	}

	private static boolean draftHasCategory(Class<?> screenClass, Object screen, Object category)
			throws Exception {
		Object edit = editor(screenClass, screen);
		return ((java.util.Set<?>) field(edit.getClass(), "categories").get(edit)).contains(category);
	}

	private static void click(Object screen, Class<?> base, int x, int y) throws Exception {
		Class<?> eventClass = Class.forName("net.minecraft.client.input.MouseButtonEvent");
		Class<?> infoClass = Class.forName("net.minecraft.client.input.MouseButtonInfo");
		Object info = infoClass.getConstructor(int.class, int.class).newInstance(0, 0);
		Object event = eventClass.getConstructor(double.class, double.class, infoClass)
				.newInstance((double) x, (double) y, info);
		base.getMethod("mouseClicked", eventClass, boolean.class).invoke(screen, event, false);
	}

	private static void type(Object screen, Class<?> base, char ch) throws Exception {
		Class<?> eventClass = Class.forName("net.minecraft.client.input.CharacterEvent");
		Object event = eventClass.getConstructor(int.class).newInstance((int) ch);
		base.getMethod("charTyped", eventClass).invoke(screen, event);
	}

	private static void key(Object screen, Class<?> base, String constant) throws Exception {
		int code = Class.forName("com.mojang.blaze3d.platform.InputConstants")
				.getField(constant).getInt(null);
		Class<?> eventClass = Class.forName("net.minecraft.client.input.KeyEvent");
		Object event = eventClass.getConstructor(int.class, int.class, int.class)
				.newInstance(code, 0, 0);
		base.getMethod("keyPressed", eventClass).invoke(screen, event);
	}

	private static void check(String what, boolean ok) {
		if (ok) {
			pass++;
			System.out.println("  OK   " + what);
		} else {
			fail++;
			System.out.println("  FAIL " + what);
		}
	}
}