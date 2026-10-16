import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.lang.reflect.Constructor;
import java.net.URLClassLoader;

/**
 * Проверка биндов на собранном jar.
 *
 * <p>Случай, из-за которого написана проверка: мод создавал бинды с зашитым
 * кодом {@code -1} («клавиша не назначена»). В 26.2 движок считает неназначенным
 * {@code InputConstants.UNKNOWN == -1}, поэтому совпадало и всё работало. В 26.3
 * движок перешёл на скан-коды SDL и завёл {@code UNKNOWN == 0}: сравнение
 * перестало срабатывать, {@code KeyMapping.setAll()} не пропускал бинд и звал
 * {@code InputConstants.isKeyDown(-1)}, а тот лез в буфер SDL по отрицательному
 * индексу. Игра падала с {@code IndexOutOfBoundsException} при входе в мир.
 *
 * <p>Проверяется именно ванильное условие ({@code shouldSetOnIngameFocus}),
 * а не наш код: если оно вернёт {@code true} для неназначенного бинда, движок
 * снова полезет в буфер ввода. Стенд не требует запуска игры.
 */
public class VerifyKeybinds {

	private static int pass;
	private static int fail;

	public static void main(String[] args) throws Exception {
		URL modJar = new java.io.File(args[0]).toURI().toURL();
		try (URLClassLoader loader = new URLClassLoader(new URL[] {modJar},
				VerifyKeybinds.class.getClassLoader())) {
			Class<?> client = loader.loadClass("nya2dtyan.makotochat.ChatTabsClient");
			Field unboundField = client.getDeclaredField("UNBOUND");
			unboundField.setAccessible(true);
			int unboundCode = (int) unboundField.get(null);

			Class<?> inputConstants = Class.forName("com.mojang.blaze3d.platform.InputConstants");
			int unknownCode = unknownValue(inputConstants);
			System.out.println("  код «не назначено» у мода: " + unboundCode
					+ ", у движка: " + unknownCode);

			check("код «не назначено» совпадает с InputConstants.UNKNOWN",
					unboundCode == unknownCode);

			Class<?> keyMapping = Class.forName("net.minecraft.client.KeyMapping");
			Class<?> category = Class.forName("net.minecraft.client.KeyMapping$Category");
			Class<?> identifier = Class.forName("net.minecraft.resources.Identifier");

			Object id = identifier.getMethod("fromNamespaceAndPath", String.class, String.class)
					.invoke(null, "makoto-chat-extension", "verify_keys");
			Object keyCategory = category.getMethod("register", identifier).invoke(null, id);

			Constructor<?> constructor = keyMapping.getConstructor(String.class, int.class, category);
			Method guard = keyMapping.getDeclaredMethod("shouldSetOnIngameFocus");
			guard.setAccessible(true);

			// Неназначенный бинд — ровно такой, какие создаёт мод.
			Object idle = constructor.newInstance("key.makoto_chat_extension.verify_idle", unboundCode,
					keyCategory);
			check("ванильная проверка пропускает неназначенный бинд",
					!(boolean) guard.invoke(idle));

			// Обратная сторона: назначенная клавиша обязана проверку проходить,
			// иначе бинды не работали бы вовсе, а стенд «проходил» бы зря.
			int space = inputConstants.getField("KEY_SPACE").getInt(null);
			Object bound = constructor.newInstance("key.makoto_chat_extension.verify_bound", space,
					keyCategory);
			check("назначенная клавиша проверку проходит", (boolean) guard.invoke(bound));

			System.out.println();
			System.out.println("ИТОГО: успешно " + pass + ", провалено " + fail);
			if (fail > 0) {
				System.exit(1);
			}
		}
	}

	/** Достаёт числовое значение поля UNKNOWN — оно разное в 26.2 и 26.3. */
	private static int unknownValue(Class<?> inputConstants) throws Exception {
		Object unknown = inputConstants.getField("UNKNOWN").get(null);
		return (int) unknown.getClass().getMethod("getValue").invoke(unknown);
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