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
			Class<?> channelClass = loader.loadClass("nya2dtyan.makotochat.MessageCategory");
			Class<?> tabClass = loader.loadClass("nya2dtyan.makotochat.ChatTab");
			Class<?> configClass = loader.loadClass("nya2dtyan.makotochat.TabConfig");
			Object config = configClass.getMethod("getInstance").invoke(null);
			// Проверки идут по вкладкам по умолчанию, поэтому состояние сбрасываем:
			// файл конфигурации мог оставить вкладки от прошлого запуска.
			configClass.getMethod("resetToDefaults").invoke(config);

			Method tabsMethod = configClass.getMethod("tabs");
			Method mainTabMethod = configClass.getMethod("mainTab");
			Method hasCategory = tabClass.getMethod("has", channelClass);
			Method categoriesOfTab = tabClass.getMethod("categories");

			Object router = routerClass.getMethod("getInstance").invoke(null);
			Object global = enumOf(channelClass, "CHAT");
			Object direct = enumOf(channelClass, "DIRECT");
			Object city = enumOf(channelClass, "CITY");
			Object core = enumOf(channelClass, "CORE_PROTECT");
			Object globalChat = enumOf(channelClass, "GLOBAL_CHAT");

			Method onIncomingChat = routerClass.getMethod("onIncomingChat", String.class);
			Method onIncomingSystem = routerClass.getMethod("onIncomingSystemMessage", String.class, boolean.class);
			Method onOutgoingChat = routerClass.getMethod("onOutgoingChat", String.class);
			Method onOutgoingCommand = routerClass.getMethod("onOutgoingCommand", String.class);
			Method select = routerClass.getMethod("select", tabClass);
			Method activeTab = routerClass.getMethod("activeTab");
			Object store = routerClass.getMethod("store").invoke(router);

			Method totalIn = store.getClass().getMethod("totalIn", tabClass);
			Method messages = store.getClass().getMethod("messages", tabClass);
			Method clearAll = store.getClass().getMethod("clearAll");

			/**
			 * Вкладка, отвечающая за категорию.
			 *
			 * <p>Раньше это была сама категория (канал). Теперь вкладка — набор
			 * категорий, и проверки идут по «своей» вкладке категории: та, у
			 * которой в наборе ровно одна категория. Обычный чат своей вкладки не
			 * имеет — он живёт в основной («Общий»), и это ожидаемо.
			 */
			java.util.function.Function<Object, Object> tabFor = ch -> {
				try {
					Object main = mainTabMethod.invoke(config);
					if (ch == global) {
						return main;
					}
					for (Object tab : (java.util.List<?>) tabsMethod.invoke(config)) {
						if ((boolean) hasCategory.invoke(tab, ch)
								&& ((java.util.Set<?>) categoriesOfTab.invoke(tab)).size() == 1) {
							return tab;
						}
					}
					throw new IllegalStateException("нет своей вкладки для " + ch);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			};

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
					return (int) totalIn.invoke(store, tabFor.apply(ch));
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			};
			java.util.function.Consumer<Object> reset = ch -> {
				try {
					clearAll.invoke(store);
					select.invoke(router, tabFor.apply(ch));
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			};

			java.util.function.Function<Object, java.util.List<?>> messagesOf = ch -> {
				try {
					return (java.util.List<?>) messages.invoke(store, tabFor.apply(ch));
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
			check("активная вкладка не сменилась сама", tabFor.apply(city), activeTab.invoke(router));

			select.invoke(router, tabFor.apply(direct));
			check("после переключения данные ЛС на месте", 2, count.apply(direct));
			check("после переключения активна ЛС", tabFor.apply(direct), activeTab.invoke(router));

			// 10. Ограничение размера вкладки
			reset.accept(global);
			for (int i = 0; i < 900; i++) {
				incoming.accept("Alex -> Steve " + i);
			}
			check("вкладка ограничена 500 сообщениями", 500, count.apply(direct));

			// 11. Сообщения не пропадают при переключении туда-сюда
			reset.accept(global);
			incoming.accept("Alex -> Steve первое");
			select.invoke(router, tabFor.apply(core));
			select.invoke(router, tabFor.apply(global));
			select.invoke(router, tabFor.apply(direct));
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
					textsOf(store, tabFor.apply(global)).stream()
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
			Method channelsOf = filter.getClass().getMethod("categoriesOf", String.class);
			// Настоящий вопрос ванильного чата: показывать ли строку в активной
			// вкладке. Именно его и проверяем — он учитывает и наборы категорий
			// вкладок, а не только категорию сообщения.
			Method inActiveTab = filter.getClass().getMethod("inActiveTab", String.class);

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
			// «Общий» показывает всё: в его наборе есть все категории, поэтому
			// ЛС-строка видна и в нём, и в «ЛС». Категория у сообщения при этом
			// одна — «Общий» не отдельная копия сообщения, а вкладка со всеми
			// категориями.
			check("у ЛС-сообщения категория ЛС", java.util.Set.of(direct),
					routingOf.apply("Alex -> Steve привет"));
			select.invoke(router, tabFor.apply(global));
			check("ЛС-строка видна в «Общ»",
					true, inActiveTab.invoke(filter, "Alex -> Steve привет"));
			select.invoke(router, tabFor.apply(direct));
			check("и видна в «ЛС»",
					true, inActiveTab.invoke(filter, "Alex -> Steve привет"));
			select.invoke(router, tabFor.apply(global));
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

			// «Общий» показывает всё, потому что в его наборе есть все категории —
			// но вкладки при этом различаются: «ЛС» не содержит реплик общего чата.
			select.invoke(router, tabFor.apply(global));
			check("в активной «Общ» ЛС-строка видна",
					true, inActiveTab.invoke(filter, "Alex -> Steve привет"));
			check("в активной «Общ» обычная реплика видна",
					true, inActiveTab.invoke(filter, "Steve: всем привет"));
			select.invoke(router, tabFor.apply(direct));
			check("в активной «ЛС» видна ЛС-строка",
					true, inActiveTab.invoke(filter, "Alex -> Steve привет"));
			check("а обычная реплика в «ЛС» не видна",
					false, inActiveTab.invoke(filter, "Steve: всем привет"));
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

			// «Общий» показывает всё: ЛС-строка видна в нём наравне со всеми.
			check("на приёме ЛС-строка видна в «Общ»",
					true, inActiveTab.invoke(filter, "Alex -> Steve привет в ЛС"));

			// Счётчик непрочитанного обязан совпадать с тем, что реально
			// попадёт в содержимое вкладки. Проверяем ДО перехода: открыть
			// вкладку — значит прочитать её, и счётчик обязан обнулиться.
			Method unreadCount = store.getClass().getMethod("unreadCount", tabClass, tabClass);
			Object mainTab = mainTabMethod.invoke(config);
			check("пока открыт «Общ», в «ЛС» ровно одно непрочитанное",
					1, (int) unreadCount.invoke(store, tabFor.apply(direct), mainTab));
			check("и ровно одно сообщение в самой вкладке",
					1, count.apply(direct));

			select.invoke(router, tabFor.apply(direct));
			check("после перехода в «ЛС» строка из «Общ» видима",
					true, routingOf.apply("Alex -> Steve привет в ЛС").contains(direct));
			check("прочитанная вкладка больше не показывает непрочитанных",
					0, (int) unreadCount.invoke(store, tabFor.apply(direct), tabFor.apply(direct)));
			check("а сообщение в ней осталось", 1, count.apply(direct));

			// 14. Автоподстановка городского «?». На вкладке «Город» игрок пишет
			// обычный текст, а уходит он с «?» — причём в поле ввода «?» не
			// видно, потому что подстановка происходит на отправке.
			Method withCityPrefix = router.getClass().getMethod("withCityPrefix", String.class);
			select.invoke(router, tabFor.apply(global));
			check("вне «Города» текст не меняется",
					"привет всем", withCityPrefix.invoke(router, "привет всем"));
			select.invoke(router, tabFor.apply(city));
			check("на «Городе» подставляется «?»",
					"?привет всем", withCityPrefix.invoke(router, "привет всем"));
			check("повторный «?» не дублируется",
					"?привет всем", withCityPrefix.invoke(router, "?привет всем"));
			check("команда на «Городе» не получает «?» — иначе /co i не отправить",
					"/co i", withCityPrefix.invoke(router, "/co i"));
			check("пустая строка не трогается",
					"", withCityPrefix.invoke(router, ""));
			select.invoke(router, tabFor.apply(global));

			// Отправленное с «?» должно лечь в «Город», а не в «Общий».
			reset.accept(global);
			select.invoke(router, tabFor.apply(city));
			outgoing.accept(withCityPrefix.invoke(router, "привет всем").toString());
			check("сообщение с «?» ушло в «Город»", 1, count.apply(city));
			check("«Общий» тоже его видит — он полон", 1, count.apply(global));
			select.invoke(router, tabFor.apply(city));
			check("в активной «Городе» видна городская реплика",
					true, routingOf.apply("?привет всем").contains(city));

			// 15. Автоподстановка «!» на вкладке «Глобальный» — ровно как «?»
			// у города, только другой символ и другой канал.
			Method withGlobalPrefix = router.getClass().getMethod("withGlobalPrefix", String.class);
			select.invoke(router, tabFor.apply(globalChat));
			check("на «Глобальном» подставляется «!»",
					"!всем привет", withGlobalPrefix.invoke(router, "всем привет"));
			check("повторный «!» не дублируется",
					"!всем привет", withGlobalPrefix.invoke(router, "!всем привет"));
			check("команда на «Глобальном» не получает «!»",
					"/co i", withGlobalPrefix.invoke(router, "/co i"));
			// «Глобальный» и «Город» не должны мешать друг другу: активна одна
			// вкладка, и префикс ставит только она. Проверяем на активной
			// вкладке, а не в выключенной.
			select.invoke(router, tabFor.apply(globalChat));
			check("на «Глобальном» городской «?» не добавляется",
					"всем привет", withCityPrefix.invoke(router, "всем привет"));
			check("но глобальный «!» добавляется",
					"!всем привет", withGlobalPrefix.invoke(router, "всем привет"));
			select.invoke(router, tabFor.apply(city));
			check("на «Городе» глобальный «!» не добавляется",
					"привет всем", withGlobalPrefix.invoke(router, "привет всем"));
			check("но городской «?» добавляется",
					"?привет всем", withCityPrefix.invoke(router, "привет всем"));
			// Вне своей вкладки не добавляется ничего.
			select.invoke(router, tabFor.apply(global));
			check("вне «Города» «?» не добавляется",
					"привет всем", withCityPrefix.invoke(router, "привет всем"));
			check("вне «Глобального» «!» не добавляется",
					"привет всем", withGlobalPrefix.invoke(router, "привет всем"));

			reset.accept(global);
			select.invoke(router, tabFor.apply(globalChat));
			outgoing.accept(withGlobalPrefix.invoke(router, "всем привет").toString());
			check("сообщение с «!» ушло в «Глобальный»", 1, count.apply(globalChat));
			check("«Общий» полон и его тоже видит", 1, count.apply(global));

			// 17. Собственное сообщение в локальный чат. Своя реплика не отличается
			// от обычного текста — сервер добавит ник и метку только в эхе, — поэтому
			// без опоры на активную вкладку она ушла бы в «Общий», а «Лок» остался
			// пустым. Именно это и было видно в игре: строка «LA Танка $ > 123».
			Object local = enumOf(channelClass, "LOCAL");
			reset.accept(global);
			select.invoke(router, tabFor.apply(local));
			outgoing.accept("123");
			check("своё сообщение на «Лок» попало в «Лок»", 1, count.apply(local));
			check("и в «Общий» тоже — он полон", 1, count.apply(global));
			check("и видно в активной вкладке", true,
					textsOf(store, tabFor.apply(local)).contains("123"));

			outgoing.accept("привет");
			check("второе своё сообщение тоже в «Лок»", 2, count.apply(local));

			// На другой вкладке то же самое в «Лок» не попадает: иначе любая
			// реплика в общем чате считалась бы локальной.
			reset.accept(global);
			select.invoke(router, tabFor.apply(global));
			outgoing.accept("обычная реплика");
			check("ввод на «Общем» не попадает в «Лок»", 0, count.apply(local));
			check("но остаётся в «Общем»", 1, count.apply(global));

			// Эхо от сервера приходит уже с ником и меткой — такое ловится разбором.
			reset.accept(global);
			select.invoke(router, tabFor.apply(global));
			incoming.accept("LA Танка $ > 123");
			incoming.accept("LA Танка $ > привет");
			check("эхо локального чата от сервера в «Лок»", 2, count.apply(local));
			check("и в «Общем»", 2, count.apply(global));
			// С §-кодами — так сервер печатает цветной чат.
			incoming.accept("§cLA Танка §7$ §f> §rпривет");
			check("локальная строка с §-кодами тоже в «Лок»", 3, count.apply(local));

			// 18. Видимость вкладок. Скрытая вкладка пропадает из полоски и из
			// переключения по кругу, но продолжает копить сообщения — иначе
			// переключение галочки обнуляло бы историю.
			Method visibleTabs = configClass.getMethod("visibleTabs");
			Method isVisibleTab = tabClass.getMethod("isVisible");
			Method setVisibleTab = tabClass.getMethod("setVisible", boolean.class);
			Method removeTab = configClass.getMethod("removeTab", tabClass);

			Object coreTab = tabFor.apply(core);
			Object mainTab2 = mainTabMethod.invoke(config);

			int before = ((java.util.List<?>) visibleTabs.invoke(config)).size();
			setVisibleTab.invoke(coreTab, false);
			int after = ((java.util.List<?>) visibleTabs.invoke(config)).size();
			check("после скрытия вкладок стало на одну меньше", before - 1, after);
			check("скрытая вкладка пропала из списка видимых",
					false, isVisibleTab.invoke(coreTab));

			// Пока вкладка скрыта, сообщения в неё всё равно приходят.
			clearAll.invoke(store);
			system.accept("§aCoreProtect§7: §fStone §7placed §fby §eSteve §72 hours ago");
			check("скрытая вкладка продолжает копить сообщения",
					1, (int) totalIn.invoke(store, coreTab));

			setVisibleTab.invoke(coreTab, true);
			check("повторное включение вернуло вкладку", true, isVisibleTab.invoke(coreTab));

			// Основная вкладка — единственная, которую нельзя удалить: в ней
			// видно всё остальное, и без неё игрок остался бы без общей ленты.
			check("основную вкладку удалить нельзя", false, removeTab.invoke(config, mainTab2));
			check("основная вкладка на месте", true, ((java.util.List<?>) tabsMethod.invoke(config))
					.contains(mainTab2));

			// Скрываем все вкладки: в полоске остаётся только вход в меню (🔧),
			// и чат при этом не должен остаться без активной вкладки.
			for (Object tab : (java.util.List<?>) tabsMethod.invoke(config)) {
				setVisibleTab.invoke(tab, false);
			}
			check("после скрытия всех вкладок видимых не осталось",
					0, ((java.util.List<?>) visibleTabs.invoke(config)).size());
			check("активной остаётся основная вкладка",
					mainTab2, activeTab.invoke(router));

			// Возвращаем всё обратно, чтобы не ломать остальные проверки.
			for (Object tab : (java.util.List<?>) tabsMethod.invoke(config)) {
				setVisibleTab.invoke(tab, true);
			}
			check("после обратного включения видимы все вкладки",
					((java.util.List<?>) tabsMethod.invoke(config)).size(),
					((java.util.List<?>) visibleTabs.invoke(config)).size());

			System.out.println();
			System.out.println("ИТОГО: успешно " + passed + ", провалено " + failed);
			if (failed > 0) {
				System.exit(1);
			}
		}
	}

	/**
	 * Читает содержимое вкладки из TabStore — список уже отформатированных строк.
	 *
	 * <p>Метод ищется по имени и числу аргументов, а не по классу аргумента:
	 * {@code messages} принимает вкладку, а вызывающий код думает категориями.
	 */
	private static java.util.List<?> messagesOf(Object store, Object tab) {
		try {
			for (Method candidate : store.getClass().getMethods()) {
				if (candidate.getName().equals("messages") && candidate.getParameterCount() == 1) {
					Object list = candidate.invoke(store, tab);
					if (list instanceof java.util.List<?> l) {
						return l;
					}
					throw new IllegalStateException("TabStore.messages вернул не список: " + list);
				}
			}
			throw new NoSuchMethodException("TabStore.messages");
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