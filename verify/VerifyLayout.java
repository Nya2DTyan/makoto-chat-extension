import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Проверка геометрии и затухания полоски вкладок на реально скомпилированном jar.
 *
 * <p>Случаи, из-за которых написаны проверки:
 * <ul>
 *   <li>полоска наезжала на середину чата, а подписи были не видны — обе ошибки
 *       арифметические, их нельзя увидеть без игры, поэтому вынесены в
 *       проверяемую функцию {@code layoutY} и константы цветов;
 *   <li>полоска не исчезала вместе с чатом — теперь гаснет по той же формуле,
 *       что и ванильный рендер, поэтому проверяются {@code chatFade} и
 *       домножение альфы {@code withAlpha}.
 * </ul>
 */
public class VerifyLayout {

	private static int pass;
	private static int fail;

	public static void main(String[] args) throws Exception {
		URL modJar = new java.io.File(args[0]).toURI().toURL();
		try (URLClassLoader loader = new URLClassLoader(new URL[] {modJar},
				VerifyLayout.class.getClassLoader())) {
			Class<?> overlay = loader.loadClass("nya2dtyan.makotochat.TabOverlay");

			// Позиция считается чистой функцией от высоты экрана — проверяем её напрямую.
			Method layoutY = findMethod(overlay, "layoutY", 1);
			check("метод layoutY(int) существует", layoutY != null);
			if (layoutY != null) {
				layoutY.setAccessible(true);

				// Верх чата в 26.2 = guiHeight - 40. Полоска должна быть на 2px ниже него.
				for (int screenHeight : new int[] {240, 360, 480, 720, 1080}) {
					int expected = screenHeight - 40 + 2;
					int got = (int) layoutY.invoke(null, screenHeight);
					check("полоска под чатом при высоте " + screenHeight + " (ожидалось y=" + expected + ")",
							got == expected);
				}
			}

			// Прозрачность: у цвета текста обязан быть непрозрачный старший байт.
			check("цвет текста непрозрачный (ARGB alpha=0xFF)", textColorHasAlpha(overlay));
			check("фон вкладки непрозрачный", backgroundHasAlpha(overlay));

			// Позиция в экране чата: вкладки стоят над полем ввода (12px) и не
			// перекрывают его. Поле ввода занимает последние 12 строк экрана.
			Method layoutYInChat = findMethod(overlay, "layoutYInChat", 1);
			check("метод layoutYInChat(int) существует", layoutYInChat != null);
			if (layoutYInChat != null) {
				layoutYInChat.setAccessible(true);
				for (int screenHeight : new int[] {240, 360, 480, 720}) {
					int barHeight = 12;
					int gap = 2;
					int expected = screenHeight - 12 - gap - barHeight;
					int got = (int) layoutYInChat.invoke(null, screenHeight);
					check("вкладки над полем ввода при высоте " + screenHeight
							+ " (ожидалось y=" + expected + ")", got == expected);
					check("вкладки не наезжают на поле ввода при высоте " + screenHeight,
							got + barHeight <= screenHeight - 12);
				}
			}

			verifyFade(overlay, loader);
			verifyAlpha(overlay);

			System.out.println();
			System.out.println("ИТОГО: успешно " + pass + ", провалено " + fail);
			if (fail > 0) {
				System.exit(1);
			}
		}
	}

	/**
	 * Затухание вместе с чатом.
	 *
	 * <p>Формула снята с байткода ванильного {@code ChatComponent$AlphaCalculator}:
	 * {@code (1 − age/200) × 10}, clamp в 0..1, результат в квадрате. Важно, что
	 * сначала вычитание и только потом умножение — с неверными скобками ноль
	 * выходит на 20-м тике, и вкладки гаснут за секунду вместо десяти.
	 */
	private static void verifyFade(Class<?> overlay, ClassLoader loader) throws Exception {
		Method chatFade = findMethod(overlay, "chatFade", 1);
		check("метод chatFade(int) существует", chatFade != null);
		if (chatFade == null) {
			return;
		}
		chatFade.setAccessible(true);

		Object router = Class.forName("nya2dtyan.makotochat.ChatRouter", true, loader)
				.getMethod("getInstance").invoke(null);
		Field lastMessageTick = router.getClass().getDeclaredField("lastMessageTick");
		lastMessageTick.setAccessible(true);

		int base = 1000;
		lastMessageTick.set(router, base);

		check("сразу после сообщения вкладки видны полностью",
				Math.abs((float) chatFade.invoke(null, base) - 1.0f) < 0.001f);
		// Полная видимость держится 9 секунд, затем затухание за последнюю.
		check("через 5 секунд вкладки ещё видны",
				(float) chatFade.invoke(null, base + 100) > 0.0f);
		check("через 9 секунд вкладки ещё видны",
				(float) chatFade.invoke(null, base + 180) > 0.0f);
		check("через 10 секунд вкладки гаснут полностью",
				(float) chatFade.invoke(null, base + 200) == 0.0f);
		check("после 10 секунд остаются скрытыми",
				(float) chatFade.invoke(null, base + 500) == 0.0f);
		check("видимость не возрастает со временем",
				(float) chatFade.invoke(null, base + 150) <= (float) chatFade.invoke(null, base + 50));
		check("затухание монотонно убывает", monotonicallyDecreasing(chatFade, base, 200));

		// Сообщений ещё не было — показывать вкладки, иначе полоса мигала бы
		// при входе в мир до первого сообщения.
		lastMessageTick.set(router, Integer.MIN_VALUE);
		check("до первого сообщения вкладки показаны",
				(float) chatFade.invoke(null, 100) == 1.0f);

		// Смена мира обнуляет счётчик тиков: возраст уходит в минус, и такое
		// тоже должно означать «показать», а не «скрыть».
		lastMessageTick.set(router, 5000);
		check("если тик обогнал сообщение, вкладки показаны",
				(float) chatFade.invoke(null, 10) == 1.0f);
	}

	/** Проверяет, что ни на одном шаге затухания видимость не подскакивает. */
	private static boolean monotonicallyDecreasing(Method chatFade, int base, int span) throws Exception {
		float previous = Float.MAX_VALUE;
		for (int age = 0; age <= span; age += 5) {
			float value = (float) chatFade.invoke(null, base + age);
			if (value > previous) {
				return false;
			}
			previous = value;
		}
		return true;
	}

	/** Альфа всех цветов вкладок домножается на видимость чата. */
	private static void verifyAlpha(Class<?> overlay) throws Exception {
		Method withAlpha = findMethod(overlay, "withAlpha", 2);
		check("метод withAlpha(int, float) существует", withAlpha != null);
		if (withAlpha == null) {
			return;
		}
		withAlpha.setAccessible(true);

		int textColor = constant(overlay, "TAB_TEXT_ACTIVE");
		int background = constant(overlay, "TAB_BG_ACTIVE");

		int faded = (int) withAlpha.invoke(null, textColor, 0.0f);
		check("при нулевой видимости текст полностью прозрачен", (faded >>> 24) == 0);
		check("при нулевой видимости фон полностью прозрачен",
				((int) withAlpha.invoke(null, background, 0.0f) >>> 24) == 0);

		int shown = (int) withAlpha.invoke(null, textColor, 1.0f);
		check("при полной видимости текст непрозрачен", (shown >>> 24) == 0xFF);
		check("при полной видимости цвет не меняется", shown == textColor);

		int half = (int) withAlpha.invoke(null, textColor, 0.5f);
		check("при половинной видимости альфа вдвое меньше", (half >>> 24) == (0xFF / 2));
		check("при затухании цвет RGB не меняется", (half & 0x00FFFFFF) == (textColor & 0x00FFFFFF));

		int over = (int) withAlpha.invoke(null, textColor, 5.0f);
		check("видимость больше единицы не ломает цвет", over == textColor);

		int negative = (int) withAlpha.invoke(null, textColor, -1.0f);
		check("отрицательная видимость не даёт отрицательной альфы",
				(negative >>> 24) == 0);
	}

	/** Ищет метод по имени и числу параметров среди объявленных. */
	private static Method findMethod(Class<?> owner, String name, int parameterCount) {
		for (Method m : owner.getDeclaredMethods()) {
			if (m.getName().equals(name) && m.getParameterCount() == parameterCount) {
				return m;
			}
		}
		return null;
	}

	/** Цвета живут в байткоде как константы — проверяем их альфу рефлексией. */
	private static boolean textColorHasAlpha(Class<?> overlay) throws Exception {
		int active = constant(overlay, "TAB_TEXT_ACTIVE");
		int inactive = constant(overlay, "TAB_TEXT_INACTIVE");
		return (active >>> 24) == 0xFF && (inactive >>> 24) == 0xFF;
	}

	private static boolean backgroundHasAlpha(Class<?> overlay) throws Exception {
		int active = constant(overlay, "TAB_BG_ACTIVE");
		int inactive = constant(overlay, "TAB_BG_INACTIVE");
		return (active >>> 24) != 0 && (inactive >>> 24) != 0;
	}

	/** Достаёт значение static final int из класса по имени. */
	private static int constant(Class<?> owner, String name) throws Exception {
		Field f = owner.getDeclaredField(name);
		f.setAccessible(true);
		return (int) f.get(null);
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