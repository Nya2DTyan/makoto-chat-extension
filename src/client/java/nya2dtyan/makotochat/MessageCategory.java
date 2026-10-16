package nya2dtyan.makotochat;

import java.util.Locale;

/**
 * Тип сообщения — то, что игрок выбирает для вкладки.
 *
 * <p>Раньше вкладка и тип сообщения были одним и тем же (канал): классификатор
 * возвращал канал, и он же рисовался в полоске. Теперь это разные вещи.
 * Категория отвечает на вопрос «что это за сообщение», а вкладка — «что игрок
 * хочет видеть вместе». Вкладка — это просто набор категорий, поэтому игрок
 * может сделать «Тикеты и ЛС» или убрать тикеты из основной вкладки.
 *
 * <p>Порядок констант задаёт порядок в списке выбора и в вкладках по умолчанию,
 * он же совпадает с порядком прежних каналов — чтобы после обновления полоска
 * выглядела как раньше.
 */
public enum MessageCategory {

	/** Всё, что не опознано как специфичное. */
	CHAT("Обычный чат", "Чат"),
	DIRECT("Личные сообщения", "ЛС"),
	CITY("Городской чат", "Город"),
	CORE_PROTECT("CoreProtect", "CO"),
	GLOBAL_CHAT("Глобальный чат", "Глоб"),
	LOCAL("Локальный чат", "Лок"),
	TICKETS("Тикеты", "Тикеты");

	private final String displayName;
	private final String shortLabel;

	MessageCategory(String displayName, String shortLabel) {
		this.displayName = displayName;
		this.shortLabel = shortLabel;
	}

	/** Полное название — для меню настроек. */
	public String displayName() {
		return displayName;
	}

	/** Короткая подпись — для узких мест вроде строки вкладки в полоске. */
	public String shortLabel() {
		return shortLabel;
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