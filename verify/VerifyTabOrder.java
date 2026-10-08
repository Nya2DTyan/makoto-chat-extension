import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Проверка порядка вкладок и перетаскивания в меню настроек на собранном jar.
 *
 * <p>Случаи, из-за которых написаны проверки:
 * <ul>
 *   <li>порядок вкладок хранился во множестве {@code Set}, которое порядок не
 *       помнит: перетаскивание было бы негде хранить, а {@code visibleChannels()}
 *       возвращал бы вкладки в порядке объявления констант;
 *   <li>служебная 🔧 всегда должна быть последней — иначе её можно было бы
 *       утащить вверх и потерять кнопку настроек в привычном месте;
 *   <li>переключение по кругу должно идти по новому порядку, а не по
 *       {@code values()}: иначе курсор прыгал бы через выключенные вкладки.
 * </ul>
 */
public class VerifyTabOrder {

	private static int pass;
	private static int fail;

	public static void main(String[] args) throws Exception {
		URL modJar = new java.io.File(args[0]).toURI().toURL();
		try (URLClassLoader loader = new URLClassLoader(new URL[] {modJar},
				VerifyTabOrder.class.getClassLoader())) {
			Class<?> settingsClass = loader.loadClass("nya2dtyan.makotochat.TabSettings");
			Class<?> channelClass = loader.loadClass("nya2dtyan.makotochat.ChatChannel");
			Class<?> screenClass = loader.loadClass("nya2dtyan.makotochat.TabSettingsScreen");

			Method getInstance = settingsClass.getMethod("getInstance");
			Object settings = getInstance.invoke(null);
			Method orderedChannels = settingsClass.getMethod("orderedChannels");
			Method visibleChannels = settingsClass.getMethod("visibleChannels");
			Method moveChannel = settingsClass.getMethod("moveChannel", int.class, int.class);
			Method cycle = settingsClass.getMethod("cycle", channelClass, int.class);
			Method setEnabled = settingsClass.getMethod("setEnabled", channelClass, boolean.class);
			Method isEnabled = settingsClass.getMethod("isEnabled", channelClass);

			Object settingsTab = enumOf(channelClass, "SETTINGS");
			Object[] all = (Object[]) orderedChannels.invoke(settings);

			check("в настройках ровно столько же вкладок, сколько каналов", all.length
					== channelClass.getEnumConstants().length);
			check("🔧 по умолчанию последняя", all[all.length - 1] == settingsTab);
			check("порядок по умолчанию совпадает с порядком констант",
					java.util.Arrays.equals(all, channelClass.getEnumConstants()));

			// Перенос первой вкладки в середину: снятая со места вкладка
			// встаёт ровно на целевой индекс, а бывшие соседи сдвигаются.
			Object firstBefore = all[0];
			Object secondBefore = all[1];
			Object thirdBefore = all[2];
			check("перенос меняет порядок", Boolean.TRUE.equals(moveChannel.invoke(settings, 0, 1)));
			Object[] after = (Object[]) orderedChannels.invoke(settings);
			check("перенесённая вкладка встала на целевой индекс", after[1] == firstBefore);
			check("бывшая вторая сдвинулась вверх", after[0] == secondBefore);
			check("остальные не сдвинулись", after[2] == thirdBefore);
			check("🔧 осталась последней", after[after.length - 1] == settingsTab);

			// 🔧 двигать нельзя.
			int last = after.length - 1;
			check("🔧 не переносится вверх",
					!Boolean.TRUE.equals(moveChannel.invoke(settings, last, 0)));
			Object[] unchanged = (Object[]) orderedChannels.invoke(settings);
			check("после попытки порядок не изменился",
					java.util.Arrays.equals(unchanged, after));

			// Нельзя выйти за границы списка.
			check("перенос за пределы списка отклоняется",
					!Boolean.TRUE.equals(moveChannel.invoke(settings, 0, after.length + 5)));
			check("перенос на себя же — не изменение",
					!Boolean.TRUE.equals(moveChannel.invoke(settings, 0, 0)));
			check("перенос с некорректным индексом отклоняется",
					!Boolean.TRUE.equals(moveChannel.invoke(settings, -1, 2)));

			// Возврат как было.
			moveChannel.invoke(settings, 1, 0);
			Object[] restored = (Object[]) orderedChannels.invoke(settings);
			check("обратный перенос вернул исходный порядок",
					java.util.Arrays.equals(restored, channelClass.getEnumConstants()));

			// visibleChannels идёт по тому же порядку и пропускает выключенные.
			Object off = all[2];
			setEnabled.invoke(settings, off, false);
			Object[] visible = (Object[]) visibleChannels.invoke(settings);
			boolean found = false;
			for (Object v : visible) {
				if (v == off) {
					found = true;
				}
			}
			check("выключенная вкладка исчезает из полоски", !found);
			check("выключенная остаётся в списке меню",
					contains((Object[]) orderedChannels.invoke(settings), off));
			check("🔧 видна всегда", contains(visible, settingsTab));
			setEnabled.invoke(settings, off, true);
			check("после обратного переключения видна снова",
					contains((Object[]) visibleChannels.invoke(settings), off));

			// Переключение по кругу идёт по порядку игрока.
			Object first = all[0];
			Object secondAgain = all[1];
			check("цикл идёт по порядку настроек, а не по values()",
					cycle.invoke(settings, first, 1) == secondAgain);
			check("цикл назад",
					cycle.invoke(settings, secondAgain, -1) == first);
			check("цикл заворачивается",
					cycle.invoke(settings, all[all.length - 1], 1) == all[0]);

			// Меню рисует тот же порядок, что и полоска.
			Method rows = screenClass.getDeclaredMethod("rows");
			rows.setAccessible(true);
			Object[] menuRows = (Object[]) rows.invoke(null);
			Object[] barRows = (Object[]) orderedChannels.invoke(settings);
			check("меню и полоска в одном порядке", java.util.Arrays.equals(menuRows, barRows));
			check("меню содержит 🔧 последней", menuRows[menuRows.length - 1] == settingsTab);
			check("isEnabled(🔧) всегда истина", Boolean.TRUE.equals(isEnabled.invoke(settings, settingsTab)));

			System.out.printf("%nИТОГО: успешно %d, провалено %d%n", pass, fail);
			if (fail > 0) {
				System.exit(1);
			}
		}
	}

	private static Object enumOf(Class<?> enumClass, String name) {
		for (Object constant : enumClass.getEnumConstants()) {
			if (((Enum<?>) constant).name().equals(name)) {
				return constant;
			}
		}
		throw new IllegalArgumentException("Нет канала " + name);
	}

	private static boolean contains(Object[] array, Object value) {
		for (Object item : array) {
			if (item == value) {
				return true;
			}
		}
		return false;
	}

	private static void check(String name, boolean ok) {
		if (ok) {
			pass++;
			System.out.println("  OK   " + name);
		} else {
			fail++;
			System.out.println("  FAIL " + name);
		}
	}
}