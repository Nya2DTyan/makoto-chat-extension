import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Проверка раскладки по вкладкам на РЕАЛЬНО скомпилированных классах мода,
 * без запуска Minecraft: jar подгружается через URLClassLoader, обращения —
 * рефлексией (часть классов package-private).
 *
 * Проверяется поведение, а не внутренности: подаём сообщение и смотрим, в какие
 * вкладки оно легло. Так тест не ломается при смене внутреннего устройства.
 *
 * Запуск: java -cp <minecraft-jars>:<fabric-jars> VerifyRouting <mod-jar>
 */
public final class VerifyRouting {

	private static int passed;
	private static int failed;

	public static void main(String[] args) throws Exception {
		URL modJar = new java.io.File(args[0]).toURI().toURL();
		try (URLClassLoader loader = new URLClassLoader(new URL[] {modJar},
				VerifyRouting.class.getClassLoader())) {

			Class<?> routerClass = loader.loadClass("nya2dtyan.makotochat.ChatRouter");
			Class<?> channelClass = loader.loadClass("nya2dtyan.makotochat.ChatChannel");

			Object router = routerClass.getMethod("getInstance").invoke(null);
			Object global = enumOf(channelClass, "GLOBAL");
			Object direct = enumOf(channelClass, "DIRECT");
			Object city = enumOf(channelClass, "CITY");
			Object core = enumOf(channelClass, "CORE_PROTECT");
			Object globalChat = enumOf(channelClass, "GLOBAL_CHAT");
			Object settingsChannel = enumOf(channelClass, "SETTINGS");

			Method onIncomingChat = routerClass.getMethod("onIncomingChat", String.class);
			Method onIncomingSystem = routerClass.getMethod("onIncomingSystemMessage", String.class, boolean.class);
			Method onOutgoingChat = routerClass.getMethod("onOutgoingChat", String.class);
			Method onOutgoingCommand = routerClass.getMethod("onOutgoingCommand", String.class);
			Method select = routerClass.getMethod("select", channelClass);
			Method activeTab = routerClass.getMethod("activeTab");
			Object store = routerClass.getMethod("store").invoke(router);

			Method totalIn = store.getClass().getMethod("totalIn", channelClass);
			Method messages = store.getClass().getMethod("messages", channelClass);
			Method clearAll = store.getClass().getMethod("clearAll");

			// Хелперы для подачи сообщений в роутер.
			java.util.function.Consumer<String> incoming = text -> {
				try {
					onIncomingChat.invoke(router, text);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			};
			java.util.function.Consumer<String> outgoing = text -> {
				try {
					onOutgoingChat.invoke(router, text);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			};
			java.util.function.Consumer<String> command = text -> {
				try {
					onOutgoingCommand.invoke(router, text);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			};
			java.util.function.Consumer<String> system = text -> {
				try {
					onIncomingSystem.invoke(router, text, false);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			};

			java.util.function.Function<Object, Integer> count = ch -> {
				try {
					return (int) totalIn.invoke(store, ch);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			};
			java.util.function.Consumer<Object> reset = ch -> {
				try {
					clearAll.invoke(store);
					select.invoke(router, ch);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			};

			java.util.function.Function<Object, java.util.List<?>> messagesOf = ch -> {
				try {
					return (java.util.List<?>) messages.invoke(store, ch);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			};

			// 1. Общий чат
			reset.accept(global);
			incoming.accept("Steve: всем привет");
			check("обычная реплика -> Общий", 1, count.apply(global));

			// 2. ЛС
			reset.accept(global);
			incoming.accept("Alex -> Steve привет, как дела");
			check("«ник -> ник» -> ЛС", 1, count.apply(direct));
			reset.accept(global);
			incoming.accept("Alex -> Steve");
			check("«ник -> ник» без текста -> ЛС", 1, count.apply(direct));

			// 3. Городской чат: свой ввод с «?»
			reset.accept(global);
			outgoing.accept("?привет всем в городе");
			check("ввод с «?» -> Город", 1, count.apply(city));

			// 4. CoreProtect: команда
			reset.accept(global);
			command.accept("/co i");
			check("команда /co i -> CoreProtect", 1, count.apply(core));

			// 5. CoreProtect: вывод сервера с цветовыми кодами
			reset.accept(global);
			system.accept("§aCoreProtect§7: §fStone §7placed §fby §eSteve §72 hours ago");
			check("вывод CoreProtect -> CoreProtect", 1, count.apply(core));

			// 6. Отказ сервера уходит в CO и в Общий — но только если он про
			// CO. Отказ чужого плагина в CO попадать не должен.
			reset.accept(global);
			system.accept("Игрок OfflinePlayer не найден");
			check("чужой отказ сервера остался в «Общем»", 1, count.apply(global));
			check("и не попал в CoreProtect", 0, count.apply(core));
			system.accept("Ошибка! Было использовано плохое слово - похуй, "
					+ "оно заменено на лисичка!");
			check("ошибка фильтра мата осталась в «Общем»",
					2, count.apply(global));
			check("и не попала в CoreProtect", 0, count.apply(core));

			// 7. Цветовые коды не ломают классификацию
			reset.accept(global);
			incoming.accept("§bAlex §7-> §aSteve §fпривет");
			check("цветной код в ЛС -> ЛС", 1, count.apply(direct));

			// 8. Оверлейные сообщения не попадают в историю
			reset.accept(global);
			java.util.function.Consumer<String> overlay = text -> {
				try {
					onIncomingSystem.invoke(router, text, true);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			};
			overlay.accept("Достижение получено!");
			check("оверлей не пишется в вкладку", 0, count.apply(global));

			// 9. ГЛАВНОЕ: вкладки живут независимо от активной
			reset.accept(city);
			incoming.accept("Alex -> Steve ты тут?");
			incoming.accept("Steve: всем привет");
			system.accept("CoreProtect: rollback complete");
			incoming.accept("Bob -> Steve привет из ЛС");

			check("ЛС накопилось, пока открыт Город", 2, count.apply(direct));
			// «Общий» полон, поэтому в нём лежат все четыре строки, а не только
			// реплики общего чата: открытая вкладка на это не влияет.
			check("Общий накопилось всё, пока открыт Город", 4, count.apply(global));
			check("CoreProtect накопилось, пока открыт Город", 1, count.apply(core));
			check("активная вкладка не сменилась сама", city, activeTab.invoke(router));

			select.invoke(router, direct);
			check("после переключения данные ЛС на месте", 2, count.apply(direct));
			check("после переключения активна ЛС", direct, activeTab.invoke(router));

			// 10. Ограничение размера вкладки
			reset.accept(global);
			for (int i = 0; i < 900; i++) {
				incoming.accept("Alex -> Steve " + i);
			}
			check("вкладка ограничена 500 сообщениями", 500, count.apply(direct));

			// 11. Сообщения не пропадают при переключении туда-сюда
			reset.accept(global);
			incoming.accept("Alex -> Steve первое");
			select.invoke(router, core);
			select.invoke(router, global);
			select.invoke(router, direct);
			check("после трёх переключений ЛС на месте", 1, count.apply(direct));

			// 11b. «Общий» обязан оставаться полным: в нём видно всё, включая
			// ЛС, город и вывод CoreProtect. Специфичные вкладки — выборки
			// из него, а не его подмножества.
			reset.accept(global);
			incoming.accept("Steve: всем привет");
			incoming.accept("Alex -> Steve привет в личку");
			command.accept("/co i");
			check("«Общий» содержит реплику общего чата", 3, count.apply(global));
			check("«Общий» содержит и ЛС-строку", true,
					textsOf(store, global).stream()
							.anyMatch(m -> m.contains("привет в личку")));
			check("«ЛС» содержит только ЛС-строку", 1, count.apply(direct));
			check("«CO» содержит только команду", 1, count.apply(core));

			// 12. ГЛАВНЫЙ РЕГРЕСС: вкладки различаются по наполнению.
			// Список видимых строк ванильный чат пересобирает только при
			// установке фильтра, поэтому раньше все вкладки показывали одно и
			// то же. Проверяем ровно то, на что смотрит рендер чата: в какие
			// вкладки роутер положил каждую строку.
			reset.accept(global);
			incoming.accept("Steve: всем привет");
			incoming.accept("Alex -> Steve привет");
			system.accept("Игрок OfflinePlayer не найден");

			Object filter = loader.loadClass("nya2dtyan.makotochat.ChatTabFilter")
					.getMethod("getInstance").invoke(null);
			Method channelsOf = filter.getClass().getMethod("channelsOf", String.class);

			java.util.function.Function<String, java.util.Set<Object>> routingOf = text -> {
				try {
					@SuppressWarnings("unchecked")
					java.util.Set<Object> got = (java.util.Set<Object>) channelsOf.invoke(filter, text);
					return got;
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			};

			check("реплика общего чата видна только в «Общ»",
					java.util.Set.of(global), routingOf.apply("Steve: всем привет"));
			// «Общий» полон: ЛС-строка лежит и в «Общем», и в «ЛС».
			check("сообщение ЛС лежит и в «Общ», и в «ЛС»",
					java.util.Set.of(global, direct), routingOf.apply("Alex -> Steve привет"));
			// Отказ чужого плагина в CO не попадает: иначе туда сыпались бы
			// все системные сообщения сервера, а не только вывод CoreProtect.
			check("чужой отказ виден только в «Общ»",
					java.util.Set.of(global), routingOf.apply("Игрок OfflinePlayer не найден"));
			check("ошибка фильтра мата видна только в «Общ»",
					java.util.Set.of(global), routingOf.apply("Ошибка! Было использовано плохое слово"));

			// Главное свойство: наполнения соседних вкладок не совпадают.
			check("наполнение «Общ» и «ЛС» различается",
					false, routingOf.apply("Steve: всем привет")
							.equals(routingOf.apply("Alex -> Steve привет")));

			// «Общий» полон, поэтому ЛС-строка в нём видна — но вкладки при этом
			// различаются: «ЛС» не содержит реплик общего чата.
			select.invoke(router, global);
			check("в активной «Общ» ЛС-строка видна",
					true, routingOf.apply("Alex -> Steve привет").contains(global));
			select.invoke(router, direct);
			check("в активной «ЛС» видна ЛС-строка",
					true, routingOf.apply("Alex -> Steve привет").contains(direct));
			check("в активной «ЛС» реплика общего чата скрыта",
					false, routingOf.apply("Steve: всем привет").contains(direct));

			// 13. РЕГРЕСС: «счётчик 1, а во вкладке пусто».
			// Фильтр спрашивается ванильным чатом не только при пересборке, но
			// и в момент приёма сообщения; отказ на приёме отбрасывал его из
			// истории целиком, и во вкладке не оставалось ничего. Проверяем, что
			// решение, отличное от «показывать в активной вкладке», на приёме
			// не мешает попасть в историю.
			reset.accept(global);
			incoming.accept("Alex -> Steve привет в ЛС");

			// «Общий» полон, поэтому ЛС-строка входит в него наравне со всеми.
			check("на приёме ЛС-строка входит в «Общ»",
					true, routingOf.apply("Alex -> Steve привет в ЛС").contains(global));

			// Счётчик непрочитанного обязан совпадать с тем, что реально
			// попадёт в содержимое вкладки. Проверяем ДО перехода: открыть
			// вкладку — значит прочитать её, и счётчик обязан обнулиться.
			Method unreadCount = store.getClass().getMethod("unreadCount", channelClass);
			check("пока открыт «Общ», в «ЛС» ровно одно непрочитанное",
					1, (int) unreadCount.invoke(store, direct));
			check("и ровно одно сообщение в самой вкладке",
					1, count.apply(direct));

			select.invoke(router, direct);
			check("после перехода в «ЛС» строка из «Общ» видима",
					true, routingOf.apply("Alex -> Steve привет в ЛС").contains(direct));
			check("прочитанная вкладка больше не показывает непрочитанных",
					0, (int) unreadCount.invoke(store, direct));
			check("а сообщение в ней осталось", 1, count.apply(direct));

			// 14. Автоподстановка городского «?». На вкладке «Город» игрок пишет
			// обычный текст, а уходит он с «?» — причём в поле ввода «?» не
			// видно, потому что подстановка происходит на отправке.
			Method withCityPrefix = router.getClass().getMethod("withCityPrefix", String.class);
			select.invoke(router, global);
			check("вне «Города» текст не меняется",
					"привет всем", withCityPrefix.invoke(router, "привет всем"));
			select.invoke(router, city);
			check("на «Городе» подставляется «?»",
					"?привет всем", withCityPrefix.invoke(router, "привет всем"));
			check("повторный «?» не дублируется",
					"?привет всем", withCityPrefix.invoke(router, "?привет всем"));
			check("команда на «Городе» не получает «?» — иначе /co i не отправить",
					"/co i", withCityPrefix.invoke(router, "/co i"));
			check("пустая строка не трогается",
					"", withCityPrefix.invoke(router, ""));
			select.invoke(router, global);

			// Отправленное с «?» должно лечь в «Город», а не в «Общий».
			reset.accept(global);
			select.invoke(router, city);
			outgoing.accept(withCityPrefix.invoke(router, "привет всем").toString());
			check("сообщение с «?» ушло в «Город»", 1, count.apply(city));
			check("«Общий» тоже его видит — он полон", 1, count.apply(global));
			select.invoke(router, city);
			check("в активной «Городе» видна городская реплика",
					true, routingOf.apply("?привет всем").contains(city));

			// 15. Автоподстановка «!» на вкладке «Глобальный» — ровно как «?»
			// у города, только другой символ и другой канал.
			Method withGlobalPrefix = router.getClass().getMethod("withGlobalPrefix", String.class);
			select.invoke(router, globalChat);
			check("на «Глобальном» подставляется «!»",
					"!всем привет", withGlobalPrefix.invoke(router, "всем привет"));
			check("повторный «!» не дублируется",
					"!всем привет", withGlobalPrefix.invoke(router, "!всем привет"));
			check("команда на «Глобальном» не получает «!»",
					"/co i", withGlobalPrefix.invoke(router, "/co i"));
			// «Глобальный» и «Город» не должны мешать друг другу: активна одна
			// вкладка, и префикс ставит только она. Проверяем на активной
			// вкладке, а не в выключенной.
			select.invoke(router, globalChat);
			check("на «Глобальном» городской «?» не добавляется",
					"всем привет", withCityPrefix.invoke(router, "всем привет"));
			check("но глобальный «!» добавляется",
					"!всем привет", withGlobalPrefix.invoke(router, "всем привет"));
			select.invoke(router, city);
			check("на «Городе» глобальный «!» не добавляется",
					"привет всем", withGlobalPrefix.invoke(router, "привет всем"));
			check("но городской «?» добавляется",
					"?привет всем", withCityPrefix.invoke(router, "привет всем"));
			// Вне своей вкладки не добавляется ничего.
			select.invoke(router, global);
			check("вне «Города» «?» не добавляется",
					"привет всем", withCityPrefix.invoke(router, "привет всем"));
			check("вне «Глобального» «!» не добавляется",
					"привет всем", withGlobalPrefix.invoke(router, "привет всем"));

			reset.accept(global);
			select.invoke(router, globalChat);
			outgoing.accept(withGlobalPrefix.invoke(router, "всем привет").toString());
			check("сообщение с «!» ушло в «Глобальный»", 1, count.apply(globalChat));
			check("«Общий» полон и его тоже видит", 1, count.apply(global));

			// 17. Собственное сообщение в локальный чат. Своя реплика не отличается
			// от обычного текста — сервер добавит ник и метку только в эхе, — поэтому
			// без опоры на активную вкладку она ушла бы в «Общий», а «Лок» остался
			// пустым. Именно это и было видно в игре: строка «LA Танка $ > 123».
			Object local = enumOf(channelClass, "LOCAL");
			reset.accept(global);
			select.invoke(router, local);
			outgoing.accept("123");
			check("своё сообщение на «Лок» попало в «Лок»", 1, count.apply(local));
			check("и в «Общий» тоже — он полон", 1, count.apply(global));
			check("и видно в активной вкладке", true,
					textsOf(store, local).contains("123"));

			outgoing.accept("привет");
			check("второе своё сообщение тоже в «Лок»", 2, count.apply(local));

			// На другой вкладке то же самое в «Лок» не попадает: иначе любая
			// реплика в общем чате считалась бы локальной.
			reset.accept(global);
			select.invoke(router, global);
			outgoing.accept("обычная реплика");
			check("ввод на «Общем» не попадает в «Лок»", 0, count.apply(local));
			check("но остаётся в «Общем»", 1, count.apply(global));

			// Эхо от сервера приходит уже с ником и меткой — такое ловится разбором.
			reset.accept(global);
			select.invoke(router, global);
			incoming.accept("LA Танка $ > 123");
			incoming.accept("LA Танка $ > привет");
			check("эхо локального чата от сервера в «Лок»", 2, count.apply(local));
			check("и в «Общем»", 2, count.apply(global));
			// С §-кодами — так сервер печатает цветной чат.
			incoming.accept("§cLA Танка §7$ §f> §rпривет");
			check("локальная строка с §-кодами тоже в «Лок»", 3, count.apply(local));

			// 18. Настройки видимости вкладок. Выключенная вкладка пропадает из
			// полоски и из переключения по кругу, но продолжает копить
			// сообщения — иначе переключение галочки обнуляло бы историю.
			Object settings = loader.loadClass("nya2dtyan.makotochat.TabSettings")
					.getMethod("getInstance").invoke(null);
			Method isEnabled = settings.getClass().getMethod("isEnabled", channelClass);
			Method toggle = settings.getClass().getMethod("toggle", channelClass);
			Method visibleChannels = settings.getClass().getMethod("visibleChannels");
			Method isConfigurable = settings.getClass().getMethod("isConfigurable", channelClass);

			check("🔧 нельзя выключить — иначе меню станет недоступно",
					false, isConfigurable.invoke(settings, settingsChannel));
			check("🔧 всегда включён",
					true, isEnabled.invoke(settings, settingsChannel));
			toggle.invoke(settings, settingsChannel);
			check("попытка выключить 🔧 не дала эффекта",
					true, isEnabled.invoke(settings, settingsChannel));

			int before = ((Object[]) visibleChannels.invoke(settings)).length;
			toggle.invoke(settings, core);
			int after = ((Object[]) visibleChannels.invoke(settings)).length;
			check("после выключения вкладок стало на одну меньше",
					before - 1, after);
			check("выключенная вкладка пропала из списка видимых",
					false, isEnabled.invoke(settings, core));
			toggle.invoke(settings, core);
			check("повторное включение вернуло вкладку",
					true, isEnabled.invoke(settings, core));

			// Выключаем всё, кроме 🔧 — так и просили: остаётся одна 🔧.
			for (Object ch : channelClass.getEnumConstants()) {
				toggle.invoke(settings, ch);
			}
			Object[] left = (Object[]) visibleChannels.invoke(settings);
			check("после выключения всего осталась одна вкладка", 1, left.length);
			check("и это 🔧", settingsChannel, left[0]);
			check("🔧 при этом включена", true, isEnabled.invoke(settings, settingsChannel));

			// Возвращаем всё обратно, чтобы не ломать остальные проверки.
			for (Object ch : channelClass.getEnumConstants()) {
				toggle.invoke(settings, ch);
			}
			check("после обратного переключения видимы все вкладки",
					channelClass.getEnumConstants().length, ((Object[]) visibleChannels.invoke(settings)).length);

			System.out.println();
			System.out.println("ИТОГО: успешно " + passed + ", провалено " + failed);
			if (failed > 0) {
				System.exit(1);
			}
		}
	}

	/** Читает содержимое вкладки из TabStore — список уже отформатированных строк. */
	private static java.util.List<?> messagesOf(Object store, Object channel) {
		try {
			Method messages = store.getClass().getMethod("messages", channel.getClass());
			Object list = messages.invoke(store, channel);
			if (list instanceof java.util.List<?> l) {
				return l;
			}
			throw new IllegalStateException("TabStore.messages вернул не список: " + list);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Тексты всех сообщений вкладки.
	 *
	 * <p>Элементы — {@code RoutedMessage}, у которых нет осмысленного
	 * {@code toString()}, поэтому берём текст компонента: именно он попадает
	 * в отрисовку, и именно его интересует проверка.
	 *
	 * <p>Текст берём через {@code getString()}, а не {@code toString()}: у
	 * компонента {@code toString()} даёт служебное «{@code literal{текст}}»,
	 * и сверка строк молча дала бы неверный результат.
	 */
	private static java.util.List<String> textsOf(Object store, Object channel) {
		Object list = messagesOf(store, channel);
		java.util.List<String> out = new java.util.ArrayList<>();
		for (Object message : (java.util.List<?>) list) {
			try {
				Object component = message.getClass().getMethod("content").invoke(message);
				out.add(String.valueOf(
						component.getClass().getMethod("getString").invoke(component)));
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		return out;
	}

	private static Object enumOf(Class<?> enumClass, String name) {
		for (Object constant : enumClass.getEnumConstants()) {
			if (((Enum<?>) constant).name().equals(name)) {
				return constant;
			}
		}
		throw new IllegalArgumentException("Нет константы " + name);
	}

	private static void check(String name, Object expected, Object actual) {
		boolean ok = expected == null ? actual == null : expected.equals(actual);
		if (ok) {
			passed++;
			System.out.println("  OK   " + name);
		} else {
			failed++;
			System.out.println("  FAIL " + name + " -> ожидалось " + expected + ", получено " + actual);
		}
	}
}