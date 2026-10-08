package nya2dtyan.makotochat;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Решает, в какую вкладку попадёт сообщение.
 *
 * <p>Логика намеренно эвристическая: у серверных плагинов нет единого протокола
 * для «это личное сообщение», поэтому входящие строки разбираются по форме.
 * Всё, что не опознано, попадает в {@link ChatChannel#GLOBAL} — вкладка «Общ»
 * всегда остаётся полной, ничего не теряется.
 *
 * <p>Порядок проверок значим:
 * <ol>
 *   <li>CoreProtect — у его вывода есть собственные узнаваемые маркеры;</li>
 *   <li>городской чат — маркер «отправитель → получатель» и префикс {@code ?};</li>
 *   <li>ЛС — «отправитель → получатель» без городского признака;</li>
 *   <li>всё остальное — общий чат.</li>
 * </ol>
 */
final class MessageClassifier {

	private MessageClassifier() {
	}

	/**
	 * «Nick1 -> Nick2 текст» — формат, который дают и ЛС, и городской чат.
	 *
	 * <p>Ник может состоять из нескольких слов: на сервере встречаются ники вида
	 * «ЛА Тянка» или «SL alive». Одно слово, как было раньше, такие отсекало, и
	 * все личные сообщения уезжали в «Общий», а вкладка «ЛС» оставалась пустой.
	 * Поэтому нику допускается до трёх слов — этого хватает на формат сервера
	 * и при этом строка вида «Steve: всем привет» под шаблон не подходит: там
	 * двоеточие, а в ник попасть не может.
	 */
	private static final Pattern ARROW = Pattern.compile(
				"([\\p{L}\\p{N}_]{1,24}(?:\\s+[\\p{L}\\p{N}_]{1,24}){0,2})\\s*(?:->|→|»|➤)\\s*([\\p{L}\\p{N}_]{1,24}(?:\\s+[\\p{L}\\p{N}_]{1,24}){0,2})\\s*:?(.*)",
				Pattern.DOTALL);

		/** «Nick1 -> Nick2» без двоеточия — некоторые плагины так пишут /msg. */
		private static final Pattern ARROW_NO_COLON = Pattern.compile(
				"^([\\p{L}\\p{N}_]{1,24}(?:\\s+[\\p{L}\\p{N}_]{1,24}){0,2})\\s*(?:->|→|»|➤)\\s*([\\p{L}\\p{N}_]{1,24}(?:\\s+[\\p{L}\\p{N}_]{1,24}){0,2})\\s*$");

	/** Префиксы команд CoreProtect, встречающиеся в тексте вывода. */
	private static final List<String> CORE_MARKERS = List.of("coreprotect", "core protect", "/co ", "co i", "co info",
			"co lookup", "co search", "co rollback", "co undo", "co redo", "co history");

	/** Слова, которые Bukkit-плагины печатают в шапке/подвале вывода CoreProtect. */
	private static final List<String> CORE_PHRASES = List.of("block details", "placed by", "first seen", "last seen",
			"coords", "coordinates", "rolled back", "rollback", "undone", "look up", "lookup results", "additions",
			"removals", "re-added", "signs", "container contents", "air", "nether", "player: ", "block: ", "world: ",
			"time ago", "days ago", "hours ago", "minutes ago", "records found", "player interaction");

	/**
	 * Строка действия в выводе CoreProtect: «39.78/m ago + alive picked up x1 shulker_box».
	 *
	 * <p>Сервер печатает возраст события сокращённо и через слеш — {@code 39.78/m ago},
	 * {@code 3.5/h ago}, {@code 12/d ago}, — а не словами, как в подсказке. Из-за
	 * этого строки действий не попадали в CO-вкладку: в ней оказывались только
	 * координаты с заголовком, а сами записи оставались в «Общем».
	 */
	private static final Pattern CORE_ACTION = Pattern.compile(
			"^\\d+(?:[.,]\\d+)?\\s*/\\s*[smhdys]\\s+ago\\b");

	/**
	 * Постраничная навигация вывода: «Page 1/121 ▶ ( 1 | 2 | 3 ... 121 )».
	 *
	 * <p>Строка относится к тому же блоку результатов, поэтому должна уехать
	 * вместе с ним, иначе в CO-вкладке она выглядит обрывком.
	 */
	private static final Pattern CORE_PAGE = Pattern.compile("^page\\s+\\d+\\s*/\\s*\\d+");

	/**
	 * Городской плагин: метки городского чата.
	 *
	 * <p>Слова-маркеры ищутся как подстроки. Сюда <b>намеренно не входит</b>
	 * префикс {@code MT}: подстрока «mt» совпала бы с «admiral mt», «team» и любым
	 * другим словом, и половина общего чата уехала бы в «Город». Префикс {@code MT}
	 * проверяется отдельно — только как отдельное слово в начале строки.
	 */
	private static final List<String> CITY_MARKERS = List.of("город", "city chat", "городской", "town chat", "город:",
				"города:", "[город]", "c:", "chat de la ville");

		/** Префикс городской метки в начале строки — как отдаёт сервер. */
	private static final String CITY_TAG = "mt";

	/**
	 * @param plainText текст сообщения уже без цветовых кодов
	 * @param outOfInput {@code true}, если сообщение — это наш собственный ввод
	 * @return канал для этого сообщения
	 */
	static ChatChannel classify(String plainText, boolean outOfInput) {
		String text = ChatChannel.normalize(plainText);
		if (text.isEmpty()) {
			return ChatChannel.GLOBAL;
		}

		if (looksLikeCoreProtect(text)) {
			return ChatChannel.CORE_PROTECT;
		}

		// Тикеты сервера печатаются с той же меткой «MT», что и городской чат,
		// поэтому их надо опознать раньше неё — иначе «MT Напоминание: у тебя
		// есть новый тикет #65064» уезжал бы в «Город».
		if (looksLikeTicket(text)) {
			return ChatChannel.TICKETS;
		}

		// Наш ввод с «?» — городской чат по договорённости с плагином.
		if (outOfInput && text.startsWith("?")) {
			return ChatChannel.CITY;
		}

		// Наш ввод с «!» — глобальный чат, по тому же принципу, что и город.
		if (outOfInput && text.startsWith("!")) {
			return ChatChannel.GLOBAL_CHAT;
		}

		// Глобальные строки сервер помечает буквой G перед ником: «G Ник реплика».
		if (looksLikeGlobalChat(text)) {
			return ChatChannel.GLOBAL_CHAT;
		}

		// Префикс «MT» в начале строки — метка городской реплики. Проверяем
		// раньше всего остального: городская строка может содержать стрелку,
		// и без этого ушла бы в «ЛС».
		if (startsWithCityTag(text)) {
			return ChatChannel.CITY;
		}

		// Строки с маркером города, но без стрелки, тоже относятся к городу.
		if (hasAny(text, CITY_MARKERS) && !text.contains("/")) {
			return ChatChannel.CITY;
		}

		// ЛС проверяем раньше локального: у ЛС есть стрелка «->», а значит
		// внутри неё есть и «>». Иначе «LA Тянка -> SL alive» разбиралась бы как
		// локальная строка (слева одно слово, справа текст) и уезжала не туда.
		if (looksLikeDirectMessage(text)) {
			return ChatChannel.DIRECT;
		}

		// События игроков (вход, выход, кик) сервер печатает со стрелкой в
		// начале строки: «-> Ник зашёл», «<- Ник был кикнут». Проверять надо
		// раньше локального чата: там «-> LA Танка» — это три слова слева от
		// «>», то есть ровно формат локальной строки, и без этой проверки
		// каждое событие входа уезжало в «Локальный».
		if (startsWithEventArrow(text)) {
			return ChatChannel.GLOBAL_CHAT;
		}

		// Локальный чат: «[метка] Ник [$] > текст». Отличительный признак —
		// «>» без получателя справа, тогда как у ЛС там стоит второй ник.
		if (looksLikeLocalChat(text)) {
			return ChatChannel.LOCAL;
		}

		return ChatChannel.GLOBAL;
	}

	/**
	 * Строка — событие игрока: вход, выход, кик, бан.
	 *
	 * <p>Признак один: стрелка стоит в самом начале строки. Именно так сервер
	 * печатает события, и именно этим они отличаются от ЛС, где стрелка стоит
	 * между ником и ником («Ник -> Ник текст» — переписка, «-> Ник зашёл» —
	 * событие).
	 *
	 * <p>Слово о событии не проверяем намеренно: формулировки сервер меняет
	 * («зашёл», «вошёл», «joined»), и список слов пришлось бы дополнять
	 * вслепую, а строка с незнакомым словом молча уехала бы в «Локальный».
	 *
	 * @param normalized текст без цветовых кодов, в нижнем регистре
	 * @return {@code true}, если строка начинается со стрелки события
	 */
	private static boolean startsWithEventArrow(String normalized) {
		return normalized.startsWith("->")
				|| normalized.startsWith("<-")
				|| normalized.startsWith("→");
	}

	/**
	 * Глобальный чат: сервер ставит букву {@code G} перед ником.
	 *
	 * <p>Формат по скриншоту — {@code G Ник реплика}, {@code G} от ника отделён
	 * пробелом. Проверяем букву отдельным словом в начале, иначе под шаблон
	 * попало бы «GTA: привет» или любая строка, начинающаяся с «g».
	 */
	private static boolean looksLikeGlobalChat(String normalized) {
		if (normalized.length() < 2 || normalized.charAt(0) != 'g') {
			return false;
		}
		// «G» обязательно отдельное слово: «g ник», а не «gta».
		char next = normalized.charAt(1);
		if (Character.isLetterOrDigit(next)) {
			return false;
		}
		// Дальше должен идти ник, а не пустота.
		return !normalized.substring(1).trim().isEmpty();
	}

	/**
	 * Локальный чат: {@code [метка] Ник [$] > текст}.
	 *
	 * <p>От ЛС отличается тем, что справа от {@code >} стоит текст реплики, а не
	 * второй ник, и двоеточия после ника нет. Разбираем строку как «слово-слово
	 * … {@code >} текст»: слева один-два слова (метка и ник, ник из двух слов
	 * разбиваем на три), справа произвольный текст.
	 */
	private static boolean looksLikeLocalChat(String normalized) {
		int arrow = normalized.indexOf('>');
		if (arrow <= 0 || arrow >= normalized.length() - 1) {
			return false;
		}
		// Двоеточие после ника — признак другого чата, а не локального.
		String left = normalized.substring(0, arrow).trim();
		if (left.endsWith(":")) {
			return false;
		}
		String right = normalized.substring(arrow + 1).trim();
		if (right.isEmpty()) {
			return false;
		}
		// Слева — метка, ник и необязательный «$». Одно слово тоже подходит:
		// «Танка > о сне» — обычный случай, а вот «Steve: …» отсеится выше
		// по двоеточию. Два-три слова — «VP polygon», «LA Танка».
		String[] parts = left.split("\\s+");
		if (parts.length < 1 || parts.length > 3) {
			return false;
		}
		if (parts[parts.length - 1].equals("$")) {
			parts = java.util.Arrays.copyOf(parts, parts.length - 1);
		}
		// Ник не может состоять из цифр и не может быть «>»-хвостом команды.
		// Значки-украшения («❖», «✦») после ника пропускаем: сервер ставит их
		// как часть оформления, и без этого «CBG GlavniBossTikTok ❖ > нет»
		// не опознавался как локальный чат.
		for (String part : parts) {
			if (isNickDecoration(part)) {
				continue;
			}
			if (!part.matches("[\\p{L}\\p{N}_$-]+")) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Значок-украшение в нике: не буква, а служебный символ.
	 *
	 * <p>Это любой не-буквенный символ, кроме «$» (суффикс ника) и «-»:
	 * сервер дописывает после ника рельс-иконку, и она не должна ломать разбор.
	 * Слева от {@code >} такие значки безопасны — с настоящей репликой их не
	 * спутать, а вот справа проверяется уже текст сообщения.
	 *
	 * @param part одно «слово» слева от {@code >}
	 * @return {@code true}, если это значок, а не часть ника
	 */
	private static boolean isNickDecoration(String part) {
		if (part.isEmpty() || part.equals("$") || part.equals("-")) {
			return false;
		}
		return part.codePoints().noneMatch(Character::isLetterOrDigit);
	}

	/**
	 * Помечена ли строка серверным маркером городского чата.
	 *
	 * <p>Нужна вызывающему коду: собственная реплика, отправленная с «?», уходит
	 * на сервер как обычный чат, и ответ плагина может вернуться в общий поток —
	 * тогда её надо продублировать в «Город».
	 */
	static boolean isCityMarked(String rawText) {
		String text = ChatChannel.normalize(rawText);
		return startsWithCityTag(text) || hasAny(text, CITY_MARKERS);
	}

	/**
	 * Начинается ли строка с городской метки {@code MT}.
	 *
	 * <p>Только как отдельное слово в начале: иначе «mt» внутри «admiral mt»
	 * или «team» сочлось бы городом и утащило туда половину общего чата.
	 */
	private static boolean startsWithCityTag(String normalized) {
		if (!normalized.startsWith(CITY_TAG)) {
			return false;
		}
		// Либо метка одна, либо после неё разделитель — «MT - Ник», «MT: реплика».
		int after = CITY_TAG.length();
		if (after >= normalized.length()) {
			return true;
		}
		char next = normalized.charAt(after);
		return !Character.isLetterOrDigit(next) && next != '_';
	}

	/**
	 * Номер тикета: {@code #65064}. Знак «#» перед цифрами — то, что отличает
	 * тикет от любой другой строки с числами.
	 */
	private static final Pattern TICKET_NUMBER = Pattern.compile("#\\d{2,}");

	/**
	 * Служебные слова, с которых начинается строка тикета за меткой «MT».
	 *
	 * <p>Проверяем именно начало остатка: у настоящей городской реплики там
	 * сразу ник, поэтому «MT тикет» — это тикет, а «MT Танка тикет» — реплика.
	 *
	 * <p>Слова «у тебя есть» и «активные» нужны для строки вида
	 * «MT У тебя есть активные тикеты: 4 Напиши /tickets…» — сервер шлёт её
	 * с меткой города, и без них она уезжала в «Город».
	 */
	private static final List<String> TICKET_WORDS = List.of("напоминание", "тикет", "тикеты",
			"закрыт", "у тебя есть", "активные");

	/**
	 * Служебные строки панели тикета, приходящие <b>без</b> метки «MT».
	 *
	 * <p>Это заголовок состояния, навигация по страницам и подписи кнопок.
	 * Список намеренно короткий и проверяется только в начале строки: слова
	 * «написать» и «закрыть» встречаются в обычной реплике, а вот строка,
	 * <i>начинающаяся</i> с такого слова и пары символов-разделителей, — это
	 * панель.
	 */
	private static final List<String> TICKET_PANEL_WORDS = List.of("| стр.", "◀ раньше", "✎ написать",
			"✖ закрыть", "✚ назад");

	/**
	 * Строка принадлежит системе тикетов сервера.
	 *
	 * <p>Формат по скриншоту открытого тикета:
	 * <pre>
	 * MT #65064
	 * | Стр. 1/1 | Сообщений: 1
	 * MT Напоминание: у тебя есть новый тикет #65064 (клик), …
	 * </pre>
	 *
	 * <p>Порядок проверок принципиален. Тикеты печатаются с той же меткой
	 * {@code MT}, что и городской чат, поэтому номер тикета сам по себе не
	 * признак: реплика «MT Танка где мой тикет #650» — это городской чат, и
	 * {@code #} в ней встречается внутри текста. Поэтому сначала смотрим на
	 * остаток после метки — служебное слово или сразу {@code #}, — и только
	 * для строк вообще без метки ищем номер и служебные слова панели.
	 */
	private static boolean looksLikeTicket(String normalized) {
		if (startsWithCityTag(normalized)) {
			String body = normalized.substring(CITY_TAG.length()).trim();
			// Номер сразу после метки — заголовок тикета «MT #65064».
			if (body.startsWith("#")) {
				return true;
			}
			// Иначе смотрим на начало остатка: у городской реплики там сразу
			// ник, а не служебное слово.
			return TICKET_WORDS.stream().anyMatch(body::startsWith);
		}
		// Строки панели приходят без метки: заголовок состояния, навигация,
		// подписи кнопок. Их опознаём по номеру тикета или служебным словам.
		if (TICKET_NUMBER.matcher(normalized).find()) {
			return true;
		}
		return TICKET_PANEL_WORDS.stream().anyMatch(normalized::startsWith);
	}

	/**
	 * Строка считается ЛС, если она выглядит как переписка двух ников
	 * («отправитель → получатель»), либо содержит явные признаки личной
	 * переписки.
	 */
	private static boolean looksLikeDirectMessage(String normalized) {
		if (ARROW_NO_COLON.matcher(normalized).matches()) {
			return true;
		}
		var matcher = ARROW.matcher(normalized);
		if (matcher.matches()) {
			String sender = matcher.group(1);
			String receiver = matcher.group(2);
			String body = matcher.group(3) == null ? "" : matcher.group(3).trim();
			// Строки вида «Nick -> Nick» без текста — это тоже ЛС (уведомление о доставке).
			// Пустое тело при этом не мешает: городской чат отсеивается выше по маркерам.
			return !sender.isBlank() && !receiver.isBlank() && (body.isEmpty() || !body.isBlank());
		}
		// Признаки личной переписки без стрелки: серверы часто пишут «Личное сообщение».
		return hasAny(normalized, List.of("личное сообщение", "лс:", "приватное сообщение", "private message",
				"whispers", "вы шепчете", "шепчет вам", "sent you a message", "tells you"));
	}

	public static boolean looksLikeCoreProtect(String normalized) {
		if (hasAny(normalized, CORE_MARKERS)) {
			return true;
		}
		// Строка действия и постраничная навигация — части блока результатов.
		if (CORE_ACTION.matcher(normalized).find() || CORE_PAGE.matcher(normalized).find()) {
			return true;
		}
		// Строки вида «^ 120, 64, -300» — координатные указатели CoreProtect.
		if (normalized.startsWith("^ ") || normalized.matches("^\\^\\s*-?\\d+.*")) {
			return true;
		}
		return hasAny(normalized, CORE_PHRASES);
	}

	private static boolean hasAny(String normalized, List<String> needles) {
		for (String needle : needles) {
			if (normalized.contains(needle)) {
				return true;
			}
		}
		return false;
	}
}