package nya2dtyan.makotochat;

import java.util.Locale;

/**
 * Канал, в который попадает сообщение. Порядок важен: классификатор проверяет
 * источники от более специфичных к более общему, чтобы CoreProtect-вывод
 * не утонул в общем чате, а городской чат не маскировался под ЛС.
 */
public enum ChatChannel {
	GLOBAL("Общий"),
	DIRECT("ЛС"),
	CITY("Город"),
	CORE_PROTECT("CoreProtect"),
	GLOBAL_CHAT("Глобальный"),
	LOCAL("Локальный"),
	TICKETS("Тикеты"),
	SETTINGS("🔧");

	private final String displayName;

	ChatChannel(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}

	/**
	 * Короткий префикс для заголовка вкладки. Держим кириллицу: вкладки
	 * рисуются тем же шрифтом, что и чат, глифы есть.
	 */
	public String tabLabel() {
		return switch (this) {
			case GLOBAL -> "Общ";
			case DIRECT -> "ЛС";
			case CITY -> "Город";
			case CORE_PROTECT -> "CO";
			case GLOBAL_CHAT -> "Глоб";
			case LOCAL -> "Лок";
			case TICKETS -> "Тикеты";
			case SETTINGS -> "🔧";
		};
	}

	public static ChatChannel byTabLabel(String label) {
		for (ChatChannel channel : values()) {
			if (channel.tabLabel().equals(label)) {
				return channel;
			}
		}
		return GLOBAL;
	}

	/**
	 * Приводит сырую строку к виду, удобному для сравнения подстрок:
	 * без регистра и с ровно одним пробелом между словами.
	 *
	 * <p>Серверные плагины печатают цветовые коды ({@code §}), hex-префиксы
	 * ({@code &#RRGGBB}) и двойные пробелы в макетах вроде CoreProtect —
	 * без нормализации они ломают любые сравнения.
	 */
	static String normalize(String raw) {
		if (raw == null) {
			return "";
		}
		String s = FormattingCodes.strip(raw);
		s = s.toLowerCase(Locale.ROOT);
		s = s.replaceAll("\\s+", " ").trim();
		return s;
	}
}