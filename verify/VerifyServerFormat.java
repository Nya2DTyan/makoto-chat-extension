import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Проверка классификатора на РЕАЛЬНЫХ строках со скриншота.
 *
 * <p>Это регрессия на конкретный формат сервера: ники из двух слов и городская
 * метка «MT». Обе формы раньше падали в «Общий» — поэтому вкладки «ЛС» и
 * «Город» оставались пустыми.
 *
 * <p>Запуск: java -cp <minecraft-jars>:<fabric-jars> VerifyServerFormat <mod-jar>
 */
public final class VerifyServerFormat {

	private static int passed;
	private static int failed;

	public static void main(String[] args) throws Exception {
		URL modJar = new java.io.File(args[0]).toURI().toURL();
		try (URLClassLoader loader = new URLClassLoader(new URL[] {modJar},
				VerifyServerFormat.class.getClassLoader())) {

			Class<?> channelClass = loader.loadClass("nya2dtyan.makotochat.MessageCategory");
			Class<?> classifier = loader.loadClass("nya2dtyan.makotochat.MessageClassifier");
			Method classify = classifier.getDeclaredMethod("classify", String.class, boolean.class);
			classify.setAccessible(true);

			// Строки со скриншота, ровно как их отдаёт сервер.
			check(classify, channelClass, "MT - ЛА Тянка хай", false, "CITY",
					"город: метка MT в начале строки");
			check(classify, channelClass, "ЛA Тянка -> SL alive > 133", false, "DIRECT",
					"ЛС: ник из двух слов слева от стрелки");
			check(classify, channelClass, "ЛA Тянка -> SL alive", false, "DIRECT",
					"ЛС: ник из двух слов, без тела");
			check(classify, channelClass, "SL alive -> ЛA Тянка привет", false, "DIRECT",
					"ЛС: свой ник из двух слов слева");
			check(classify, channelClass, "SL alive -> ЛA Тянка", false, "DIRECT",
					"ЛС: два ника из двух слов");

			// Метка города не должна срабатывать как подстрока чужих слов:
			// «mt» внутри слова — не метка.
			check(classify, channelClass, "Steve: admiral mt", false, "CHAT",
					"«mt» внутри слова не считается городом");
			check(classify, channelClass, "team build", false, "CHAT",
					"«team» не считается городом");
			check(classify, channelClass, "Steve: всем привет", false, "CHAT",
					"обычная реплика остаётся общей");

			// CoreProtect должен опознаваться раньше остального.
			check(classify, channelClass, "§aCoreProtect§7: §fStone §7placed §fby §eSteve", false,
					"CORE_PROTECT", "вывод CoreProtect опознаётся");

			// Городская строка не должна утекать в «ЛС», даже если в ней стрелка.
			check(classify, channelClass, "MT - ЛА Тянка -> кто-то", false, "CITY",
					"городская строка со стрелкой остаётся городом");

			// Наш собственный ввод в город — по договорённости с плагином «?».
			check(classify, channelClass, "?привет всем", true, "CITY",
					"ввод с «?» — город");

			// Реальные строки вывода CoreProtect со скриншота: в CO-вкладке
			// должны быть и координаты, и сами записи действий, и навигация.
			// Раньше уезжали только координаты, а записи оставались в «Общем».
			check(classify, channelClass, "39.78/m ago alive clicked warped_trapdoor", false,
					"CORE_PROTECT", "строка действия CoreProtect с возрастом в минутах");
			check(classify, channelClass, "41.17/m ago + alive picked up x1 shulker_box", false,
					"CORE_PROTECT", "строка действия CoreProtect с добавлением");
			check(classify, channelClass, "41.20/m ago - alive broke shulker_box", false,
					"CORE_PROTECT", "строка действия CoreProtect с удалением");
			check(classify, channelClass, "3.5/h ago alive placed stone", false,
					"CORE_PROTECT", "строка действия CoreProtect с возрастом в часах");
			check(classify, channelClass, "12/d ago alive broke dirt", false,
					"CORE_PROTECT", "строка действия CoreProtect с возрастом в днях");
			check(classify, channelClass, "Page 1/121 ( 1 | 2 | 3 ... 121 )", false,
					"CORE_PROTECT", "постраничная навигация CoreProtect");
			check(classify, channelClass, "----- CoreProtect | Lookup Results -----", false,
					"CORE_PROTECT", "шапка результатов CoreProtect");
			check(classify, channelClass, "CoreProtect - Lookup searching. Please wait...", false,
					"CORE_PROTECT", "статус поиска CoreProtect");
			check(classify, channelClass, "CoreProtect - No results found.", false,
					"CORE_PROTECT", "пустой результат CoreProtect");

			// Чужие строки не должны попадать в CO из-за новых правил: обычная
			// реплика про возраст не имеет формата «N/m ago».
			check(classify, channelClass, "Steve: 5/m ago я тут", false, "CHAT",
					"обычная реплика про возраст остаётся общей");
			check(classify, channelClass, "Steve: page 1 of the book", false, "CHAT",
					"«page 1» без дроби — не навигация CO");

			// Локальный чат — реальные строки со скриншота. Формат:
			// «[метка] Ник [$] > текст», двоеточия после ника нет.
			check(classify, channelClass, "LA Тянка $ > хай", false, "LOCAL",
					"локальный: метка, ник из двух слов и «$»");
			check(classify, channelClass, "LA Тянка $ > напишите чото в локал пж", false, "LOCAL",
					"локальный: длинная реплика после «$»");
			check(classify, channelClass, "VP polygon > пр", false, "LOCAL",
					"локальный: метка и ник без «$»");
			check(classify, channelClass, "VP polygon > я разобралась", false, "LOCAL",
					"локальный: без «$», длинный текст");
			check(classify, channelClass, "Танка > о сне", false, "LOCAL",
					"локальный: только ник, без метки");

			// Локальный не должен забирать ЛС: у ЛС стрелка «->», и в ней тоже
			// есть «>». Порядок проверок это разруливает.
			check(classify, channelClass, "LA Тянка -> SL alive > 133", false, "DIRECT",
					"ЛС не перехватывается локальным");
			check(classify, channelClass, "Alex -> Steve привет", false, "DIRECT",
					"простое ЛС не перехватывается локальным");

			// Глобальный чат помечается буквой G перед ником.
			check(classify, channelClass, "G Ник всем привет", false, "GLOBAL_CHAT",
					"глобальный: буква G перед ником");
			check(classify, channelClass, "G LA Тянка хай", false, "GLOBAL_CHAT",
					"глобальный: G и ник из двух слов");
			// «G» должна быть отдельным словом, иначе под шаблон попало бы
			// начало любой строки на «g».
			check(classify, channelClass, "GTA: всем привет", false, "CHAT",
					"«GTA» не считается глобальным чатом");
			check(classify, channelClass, "Steve: всем привет", false, "CHAT",
					"обычная реплика остаётся общей");
			check(classify, channelClass, "good day всем", false, "CHAT",
					"слово «good» не считается меткой G");

			// Собственный ввод: «?» — город, «!» — глобальный.
			check(classify, channelClass, "!всем привет", true, "GLOBAL_CHAT",
					"наш ввод с «!» — глобальный");

			// Тикеты. Печатаются с той же меткой «MT», что и городской чат, но
			// в «Город» попадать не должны — из-за этого их и вынесли отдельно.
			check(classify, channelClass, "MT Напоминание: у тебя есть новый тикет #65064 (клик), "
					+ "на который ты еще не отвечал. Отправь сообщение в чат или Discord!", false, "TICKETS",
					"напоминание о тикете — не город");
			check(classify, channelClass, "MT #65064", false, "TICKETS",
					"заголовок тикета с номером");
			check(classify, channelClass, "| Стр. 1/1 | Сообщений: 1", false, "TICKETS",
					"строка состояния панели тикета");
			// Обычная городская реплика про тикет не должна уезжать в «Тикеты»:
			// там сразу идёт ник, а не служебное слово.
			check(classify, channelClass, "MT Танка где мой тикет #650", false, "CITY",
					"городская реплика с номером в тексте остаётся городом");
			check(classify, channelClass, "MT - ЛА Танка хай", false, "CITY",
					"обычная городская реплика на месте");

			// Вход и выход игроков. Строки со скриншотов сервера: стрелка в
			// начале строки. Раньше «-> LA Танка зашёл» разбиралась как локальный
			// чат («-> LA Танка» — три слова слева от «>»), и каждое событие
			// входа оказывалось в «Локальном».
			check(classify, channelClass, "-> LA Танка зашёл на сервер", false, "GLOBAL_CHAT",
					"вход игрока — глобальный чат, а не локальный");
			check(classify, channelClass, "-> LA Танка вышел с сервера", false, "GLOBAL_CHAT",
					"выход игрока — глобальный чат");
			check(classify, channelClass, "§7-> §fLA Танка §7вошёл в игру", false, "GLOBAL_CHAT",
					"вход с цветовыми кодами");
			check(classify, channelClass, "<- Бор VP polygon была кикнута за то, что слишком крутая для вас",
			false, "GLOBAL_CHAT", "кик игрока");
			check(classify, channelClass, "-> LA Танка вышел с сервера (перезаход)", false,
			"GLOBAL_CHAT", "строка события с любой формулировкой");
			
			// Проверяем строго: у события признак один — стрелка в начале.
			// Слова о входе/выходе не требуются, иначе незнакомая формулировка
			// молча уехала бы в «Локальный». Плата за это — реплика, которую
			// игрок начал со стрелки, считается событием: сервер такие не шлёт.
			check(classify, channelClass, "-> LA Танка хай", false, "GLOBAL_CHAT",
			"стрелка в начале без слова о событии — тоже событие");
			
			// И самое важное: обычное ЛС не должно сломаться от новой проверки.
			check(classify, channelClass, "LA Танка -> SL alive > 133", false, "DIRECT",
			"ЛС по-прежнему в «ЛС», а не в глобальный");
			check(classify, channelClass, "Alex -> Steve привет", false, "DIRECT",
			"простое ЛС на месте");
			// Стрелка сразу после ника — это переписка, а не событие.
			check(classify, channelClass, "SL alive -> Steve привет", false, "DIRECT",
			"ЛС, где стрелка сразу после ника, остаётся перепиской");
			check(classify, channelClass, "LA Танка $ > 123", false, "LOCAL",
					"локальный чат не сломан новой проверкой");
			check(classify, channelClass, "VP polygon > пр", false, "LOCAL",
					"локальный чат без метки на месте");

			// Активные тикеты. Сервер шлёт их с меткой города «MT», но это
			// служебная строка тикетов — без слов «у тебя есть» и «активные»
			// в словнике она уезжала в «Город».
			check(classify, channelClass,
					"MT У тебя есть активные тикеты: 4 Напиши /tickets или нажми на неё для просмотра",
					false, "TICKETS", "напоминание об активных тикетах — не город");
			check(classify, channelClass, "MT Активные тикеты: 4", false, "TICKETS",
					"короткая строка об активных тикетах");
			check(classify, channelClass, "MT У тебя есть новый тикет #65064 (клик)", false,
					"TICKETS", "напоминание о новом тикете");
			// При этом обычная городская реплика про тикет остаётся городом:
			// там сразу идёт ник, а не служебное слово.
			check(classify, channelClass, "MT Танка где мой тикет #650", false, "CITY",
					"городская реплика про тикет не уезжает в «Тикеты»");

			// Значок-украшение после ника. Стоял у «GlavniBossTikTok», и без
			// его обработки строка уходила в «Общий», а в «Локальном» из трёх
			// реплик оставалась одна.
			check(classify, channelClass, "CBG GlavniBossTikTok ❖ > нет", false, "LOCAL",
					"локальный с украшением после ника");
			check(classify, channelClass, "§7CBG §bGlavniBossTikTok §x❖ §f> нет", false, "LOCAL",
					"локальный с украшением и цветовыми кодами");
			check(classify, channelClass, "✦ Steve > привет", false, "LOCAL",
					"значок вместо метки");
			check(classify, channelClass, "LA Танка $ > напишите в локальный чат", false, "LOCAL",
					"локальный с суффиксом «$» после ника");
			check(classify, channelClass, "WP Damaron1231 > не", false, "LOCAL",
					"локальный с меткой и цифрами в нике");

			System.out.println();
			System.out.println("ИТОГО: успешно " + passed + ", провалено " + failed);
			if (failed > 0) {
				System.exit(1);
			}
		}
	}

	private static void check(Method classify, Class<?> channelClass, String text, boolean outOfInput,
			String expectedName, String description) throws Exception {
		Object actual = classify.invoke(null, text, outOfInput);
		String actualName = ((Enum<?>) actual).name();
		if (actualName.equals(expectedName)) {
			passed++;
			System.out.println("  OK   " + description + "  [" + text + "] -> " + actualName);
		} else {
			failed++;
			System.out.println("  FAIL " + description + "  [" + text + "] -> ожидалось "
					+ expectedName + ", получено " + actualName);
		}
	}

	private static Object enumOf(Class<?> enumClass, String name) {
		for (Object constant : enumClass.getEnumConstants()) {
			if (((Enum<?>) constant).name().equals(name)) {
				return constant;
			}
		}
		throw new IllegalArgumentException("Нет константы " + name);
	}
}