package nya2dtyan.makotochat;

/**
 * Убирает форматирование, которое серверные плагины печатают в чат.
 *
 * <p>Что обрабатывается и почему:
 * <ul>
 *   <li><b>legacy §-коды</b> ({@code §a}, {@code §l}) — так Bukkit-плагины задают цвет,
 *       и именно в этом виде {@code Component#getString()} отдаёт их клиенту;</li>
 *   <li><b>hex-формы</b> ({@code §#RRGGBB}, {@code §x§R§R§G§G§B§B}) — в 26.x серверы
 *       чаще пишут цвета в hex.</li>
 * </ul>
 *
 * <p>Форма с {@code &} обрабатывается <b>только</b> перед {@code #} (hex). Одиночные
 * {@code &цвет} не трогаем: {@code &} неотличим от амперсанда в обычном тексте, и
 * «R&amp;D team» превратилось бы в «Rteam», а «a &amp; b» — в обрезанную строку.
 * В чат {@code &}-формы приходят уже преобразованными в {@code §}; исключение —
 * hex, который некоторые плагины печатают как {@code &#RRGGBB} дословно.
 */
final class FormattingCodes {

	private FormattingCodes() {
	}

	/**
	 * @param text исходная строка как её отдаёт {@code Component#getString()}
	 * @return строка без цветовых кодов и прочих маркеров форматирования
	 */
	static String strip(String text) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		StringBuilder out = new StringBuilder(text.length());
		int i = 0;
		int n = text.length();
		while (i < n) {
			char c = text.charAt(i);
			boolean isSection = c == SECTION_SIGN;
			// '&' тоже маркер, но только когда за ним идёт '#': иначе это амперсанд в тексте.
			boolean ampersand = c == AMPERSAND && i + 1 < n && text.charAt(i + 1) == '#';
			if ((!isSection && !ampersand) || i + 1 >= n) {
				out.append(c);
				i++;
				continue;
			}

			char next = text.charAt(i + 1);

			// §#RRGGBB и &#RRGGBB — hex одним куском.
			if (next == '#') {
				int digits = countHexRun(text, i + 2);
				if (digits == 6) {
					// Пропускаем маркер (§ или &), '#' и сами шесть цифр.
					i += 2 + 6;
					continue;
				}
				out.append(c);
				i++;
				continue;
			}

			// §x§R§R§G§G§B§B — hex посимвольно; §x просто пропускаем.
			if (next == 'x' || next == 'X') {
				i += 2;
				continue;
			}

			// Одиночный legacy-код: § + ровно один символ.
			if (isFormatChar(next)) {
				i += 2;
				continue;
			}

			out.append(c);
			i++;
		}
		return out.toString();
	}

	/**
	 * Считает, сколько hex-цифр подряд идёт начиная с позиции.
	 *
	 * @return число цифр (0..6), либо 0, если сразу не hex-цифра
	 */
	private static int countHexRun(String text, int start) {
		int i = start;
		int n = text.length();
		int digits = 0;
		while (i < n && digits < 6 && isHexDigit(text.charAt(i))) {
			i++;
			digits++;
		}
		return digits;
	}

	private static boolean isHexDigit(char c) {
		return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
	}

	/** Символы, после которых {@code §} означает форматирование, а не текст. */
	private static boolean isFormatChar(char c) {
		if (isHexDigit(c)) {
			return true;
		}
		return c == 'k' || c == 'l' || c == 'm' || c == 'n' || c == 'o' || c == 'r';
	}

	private static final char SECTION_SIGN = '§';

	private static final char AMPERSAND = '&';
}