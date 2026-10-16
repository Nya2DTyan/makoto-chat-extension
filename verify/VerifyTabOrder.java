import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Проверка вкладок игрока на собранном jar: добавление, удаление,
 * переименование, категории и сохранение в файл.
 *
 * <p>Случаи, из-за которых написана проверка:
 * <ul>
 *   <li>вкладка была жёстко привязана к каналу, и убрать тип сообщений из
 *       «Общего» было нельзя — теперь вкладка это набор категорий, и проверяется,
 *       что снятая категория действительно перестаёт в неё попадать;</li>
 *   <li>основную вкладку нельзя удалить — иначе в чате стало бы не видно всё
 *       остальное;</li>
 *   <li>вкладки с именем игрока должны переживать перезапуск, поэтому файл
 *       конфигурации проверяется записью и чтением.</li>
 * </ul>
 */
public class VerifyTabOrder {

	private static int pass;
	private static int fail;

	public static void main(String[] args) throws Exception {
		URL modJar = new java.io.File(args[0]).toURI().toURL();
		try (URLClassLoader loader = new URLClassLoader(new URL[] {modJar},
				VerifyTabOrder.class.getClassLoader())) {

			Class<?> configClass = loader.loadClass("nya2dtyan.makotochat.TabConfig");
			Class<?> tabClass = loader.loadClass("nya2dtyan.makotochat.ChatTab");
			Class<?> categoryClass = loader.loadClass("nya2dtyan.makotochat.MessageCategory");
			Object config = configClass.getMethod("getInstance").invoke(null);

			Method tabs = configClass.getMethod("tabs");
			Method visibleTabs = configClass.getMethod("visibleTabs");
			Method addTab = configClass.getMethod("addTab", String.class, Set.class);
			Method removeTab = configClass.getMethod("removeTab", tabClass);
			Method move = configClass.getMethod("move", int.class, int.class);
			Method mainTab = configClass.getMethod("mainTab");
			Method activeTab = configClass.getMethod("activeTab");
			Method setActiveTab = configClass.getMethod("setActiveTab", tabClass);
			Method resetToDefaults = configClass.getMethod("resetToDefaults");

			Method name = tabClass.getMethod("name");
			Method categories = tabClass.getMethod("categories");
			Method has = tabClass.getMethod("has", categoryClass);
			Method setCategory = tabClass.getMethod("setCategory", categoryClass, boolean.class);
			Method setName = tabClass.getMethod("setName", String.class);
			Method isMain = tabClass.getMethod("isMain");
			Method isVisible = tabClass.getMethod("isVisible");
			Method setVisible = tabClass.getMethod("setVisible", boolean.class);
			Method showsAny = tabClass.getMethod("showsAny", Set.class);

			// --- вкладки по умолчанию -------------------------------------
			resetToDefaults.invoke(config);
			List<?> defaults = (List<?>) tabs.invoke(config);
			check("вкладок по умолчанию столько же, сколько было раньше (7)",
					defaults.size() == 7);
			check("основная вкладка называется «Общий»",
					"Общий".equals(name.invoke(mainTab.invoke(config))));
			check("основная вкладка помечена как основная",
					(boolean) isMain.invoke(mainTab.invoke(config)));
			check("основная вкладка содержит все типы сообщений",
					((Set<?>) categories.invoke(mainTab.invoke(config))).size()
							== categoryClass.getEnumConstants().length);
			check("все вкладки по умолчанию видимы",
					visibleTabs.invoke(config).equals(tabs.invoke(config)));

			// --- главное требование: категория убирается из «Общего» --------
			Object tickets = enumConstant(categoryClass, "TICKETS");
			Object main = mainTab.invoke(config);
			Object ticketsTab = findTab(tabs, name, "Тикеты", config);
			check("есть вкладка «Тикеты»", ticketsTab != null);

			Object ticketMessage = enumSet(categoryClass, tickets);
			check("до правки тикет виден в «Общем»", (boolean) showsAny.invoke(main, ticketMessage));
			check("и в своей вкладке", (boolean) showsAny.invoke(ticketsTab, ticketMessage));

			setCategory.invoke(main, tickets, false);
			check("после снятия галочки тикет исчез из «Общего»",
					!(boolean) showsAny.invoke(main, ticketMessage));
			check("но остался во вкладке «Тикеты»",
					(boolean) showsAny.invoke(ticketsTab, ticketMessage));
			check("остальные типы сообщений в «Общем» не задеты",
					(boolean) showsAny.invoke(main, enumSet(categoryClass,
							enumConstant(categoryClass, "DIRECT"))));

			// --- своя вкладка ----------------------------------------------
			Object direct = enumConstant(categoryClass, "DIRECT");
			Object created = addTab.invoke(config, "Мои ЛС", enumSet(categoryClass, direct, tickets));
			check("вкладка создаётся", created != null);
			check("её название сохраняется", "Мои ЛС".equals(name.invoke(created)));
			check("в неё попадает выбранный тип сообщений",
					(boolean) showsAny.invoke(created, enumSet(categoryClass, direct)));
			check("и второй выбранный тип",
					(boolean) showsAny.invoke(created, ticketMessage));
			check("а невыбранный не попадает",
					!(boolean) showsAny.invoke(created, enumSet(categoryClass,
							enumConstant(categoryClass, "CITY"))));
			check("новая вкладка видна в полоске",
					((List<?>) visibleTabs.invoke(config)).contains(created));

			// --- переименование --------------------------------------------
			setName.invoke(created, "Торговля");
			check("вкладка переименовывается", "Торговля".equals(name.invoke(created)));

			// --- скрытие ----------------------------------------------------
			setVisible.invoke(created, false);
			check("скрытая вкладка исчезает из полоски",
					!((List<?>) visibleTabs.invoke(config)).contains(created));
			check("но остаётся в списке настроек",
					((List<?>) tabs.invoke(config)).contains(created));

			// Переключение работает только по видимым вкладкам.
			setActiveTab.invoke(config, created);
			check("скрытую вкладку нельзя сделать активной",
					activeTab.invoke(config) != created);
			setVisible.invoke(created, true);

			// --- удаление ---------------------------------------------------
			check("своя вкладка удаляется", (boolean) removeTab.invoke(config, created));
			check("удалённой вкладки больше нет в списке",
					!((List<?>) tabs.invoke(config)).contains(created));
			check("основную вкладку удалить нельзя",
					!(boolean) removeTab.invoke(config, mainTab.invoke(config)));
			check("основная вкладка на месте",
					"Общий".equals(name.invoke(mainTab.invoke(config))));

			// --- перестановка ----------------------------------------------
			resetToDefaults.invoke(config);
			List<?> before = new ArrayList<>((List<?>) tabs.invoke(config));
			check("перестановка вкладок работает", (boolean) move.invoke(config, 1, 3));
			List<?> after = new ArrayList<>((List<?>) tabs.invoke(config));
			check("первая вкладка осталась первой", after.get(0) == before.get(0));
			check("переставленная вкладка встала на новое место", after.get(3) == before.get(1));
			check("основную вкладку переставить нельзя", !(boolean) move.invoke(config, 0, 2));

			// --- сохранение в файл -------------------------------------------
			verifyFile(loader, configClass, tabClass, categoryClass, config, tickets);

			System.out.println();
			System.out.println("ИТОГО: успешно " + pass + ", провалено " + fail);
			if (fail > 0) {
				System.exit(1);
			}
		}
	}

	/**
	 * Проверяет, что вкладки переживают перезапуск: сохраняем, читаем файл
	 * заново и сверяем названия и категории.
	 */
	private static void verifyFile(ClassLoader loader, Class<?> configClass, Class<?> tabClass,
			Class<?> categoryClass, Object config, Object tickets) throws Exception {
		Path temp = Files.createTempFile("makoto-tabs", ".json");
		try {
			Class<?> fileClass = loader.loadClass("nya2dtyan.makotochat.TabConfigFile");
			Constructor<?> constructor = fileClass.getConstructor(Path.class);
			Object storage = constructor.newInstance(temp);
			configClass.getMethod("bindStorage", loader.loadClass("nya2dtyan.makotochat.TabConfig$Storage"))
					.invoke(config, storage);

			configClass.getMethod("resetToDefaults").invoke(config);
			configClass.getMethod("addTab", String.class, Set.class)
					.invoke(config, "Тикеты и ЛС", enumSet(categoryClass, tickets));

			Object reread = constructor.newInstance(temp);
			List<?> loaded = (List<?>) fileClass.getMethod("read").invoke(reread);
			check("файл конфигурации прочитался", loaded != null && !loaded.isEmpty());
			if (loaded == null) {
				return;
			}
			Object restored = findTabByName(loaded, tabClass, "Тикеты и ЛС");
			check("своя вкладка пережила перезапуск", restored != null);
			if (restored != null) {
				check("её категории сохранились",
						(boolean) tabClass.getMethod("has", categoryClass).invoke(restored, tickets));
			}
			check("основная вкладка в файле одна",
					countMain(loaded, tabClass) == 1);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private static int countMain(List<?> tabs, Class<?> tabClass) throws Exception {
		Method isMain = tabClass.getMethod("isMain");
		int count = 0;
		for (Object tab : tabs) {
			if ((boolean) isMain.invoke(tab)) {
				count++;
			}
		}
		return count;
	}

	private static Object findTab(Method tabs, Method name, String wanted, Object config)
			throws Exception {
		return findTabByName((List<?>) tabs.invoke(config), null, wanted);
	}

	private static Object findTabByName(List<?> tabs, Class<?> tabClass, String wanted)
			throws Exception {
		for (Object tab : tabs) {
			String value = (String) tab.getClass().getMethod("name").invoke(tab);
			if (wanted.equals(value)) {
				return tab;
			}
		}
		return null;
	}

	private static Object enumConstant(Class<?> enumClass, String name) {
		for (Object value : enumClass.getEnumConstants()) {
			if (((Enum<?>) value).name().equals(name)) {
				return value;
			}
		}
		throw new IllegalArgumentException("нет константы " + name);
	}

	/** Набор категорий для проверки: то, что игрок отмечает галочками. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Set<Object> enumSet(Class<?> enumClass, Object... values) {
		EnumSet set = EnumSet.noneOf((Class<Enum>) enumClass);
		for (Object value : values) {
			set.add((Enum) value);
		}
		return set;
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