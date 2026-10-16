package nya2dtyan.makotochat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Вкладки игрока: что показывать в полоске и какие категории в какую вкладку
 * попадают.
 *
 * <p>Раньше вкладки были жёстко зашиты перечислением каналов, и «Общий» был
 * особым случаем: он показывал всё, и выключить в нём отдельный тип сообщений
 * было нельзя. Теперь вкладка — это название плюс набор категорий, поэтому
 * «Общий» ничем не отличается от остальных: его категории — такие же галочки,
 * и тикеты из него убираются одной из них.
 *
 * <p>Хранилище состояния отдельное (см. {@link #store}), потому что вопрос
 * «что игрок хочет видеть» не равен вопросу «где лежит сообщение»: скрытая
 * вкладка продолжает копить сообщения, они просто не показываются.
 *
 * <p>🔧 — не вкладка, а вход в меню настроек; она живёт вне этого списка и
 * всегда доступна.
 */
public final class TabConfig {

	/** Сколько вкладок разрешаем завести — чтобы полоска не уехала за экран. */
	public static final int MAX_TABS = 16;

	private static TabConfig instance;

	private final List<ChatTab> tabs = new ArrayList<>();

	private ChatTab active;

	/**
	 * Куда сохранять изменения. {@code null} — не сохранять: так модель ведёт
	 * себя в стендах проверки, где файловой конфигурации нет.
	 */
	private TabConfig.Storage storage;

	/** Хранилище конфигурации: чтение при входе и запись после правок. */
	public interface Storage {

		/** Читает вкладки. {@code null} — файла нет или он нечитаем. */
		List<ChatTab> read();

		void write(List<ChatTab> tabs);
	}

	private TabConfig() {
		tabs.addAll(defaultTabs());
		active = tabs.get(0);
	}

	public static synchronized TabConfig getInstance() {
		if (instance == null) {
			instance = new TabConfig();
		}
		return instance;
	}

	/**
	 * Вкладки по умолчанию: ровно те, что были до переделки, чтобы обновление
	 * не меняло привычную полоску. «Общий» содержит все категории — то есть
	 * по-прежнему показывает всё.
	 */
	public static List<ChatTab> defaultTabs() {
		List<ChatTab> out = new ArrayList<>();
		out.add(new ChatTab("Общий", EnumSet.allOf(MessageCategory.class), true));
		for (MessageCategory category : MessageCategory.values()) {
			if (category == MessageCategory.CHAT) {
				// Обычный чат виден только в «Общем»: отдельной вкладки под него
				// раньше не было, и заводить её молча — менять вид игры.
				continue;
			}
			out.add(new ChatTab(category.shortLabel(), EnumSet.of(category), false));
		}
		return out;
	}

	/** Все вкладки в порядке полоски, включая скрытые. */
	public List<ChatTab> tabs() {
		return Collections.unmodifiableList(tabs);
	}

	/** Вкладки, видимые в полоске, — по ним идёт и переключение по кругу. */
	public List<ChatTab> visibleTabs() {
		List<ChatTab> out = new ArrayList<>();
		for (ChatTab tab : tabs) {
			if (tab.isVisible()) {
				out.add(tab);
			}
		}
		return out;
	}

	/** Основная вкладка — та, что нельзя удалить. */
	public ChatTab mainTab() {
		for (ChatTab tab : tabs) {
			if (tab.isMain()) {
				return tab;
			}
		}
		// Конфигурация без основной вкладки невозможна: сюда попадаем только
		// при испорченном файле, и тогда чиним список сами.
		ChatTab main = new ChatTab("Общий", EnumSet.allOf(MessageCategory.class), true);
		tabs.add(0, main);
		return main;
	}

	public ChatTab activeTab() {
		// Активная вкладка могла быть удалена или скрыта — тогда показываем
		// основную, иначе в чате не было бы видно ничего.
		if (active == null || !visibleTabs().contains(active)) {
			active = visibleTabs().isEmpty() ? mainTab() : visibleTabs().get(0);
		}
		return active;
	}

	public void setActiveTab(ChatTab tab) {
		if (tab != null && tabs.contains(tab)) {
			active = tab;
		}
	}

	/**
	 * Заводит вкладку с заданным названием и категориями.
	 *
	 * @return созданную вкладку или {@code null}, если места больше нет
	 */
	public ChatTab addTab(String name, Set<MessageCategory> categories) {
		if (tabs.size() >= MAX_TABS) {
			return null;
		}
		ChatTab tab = new ChatTab(name, categories, false);
		tabs.add(tab);
		save();
		return tab;
	}

	/** Удаляет вкладку. Основную удалить нельзя — в ней видно всё остальное. */
	public boolean removeTab(ChatTab tab) {
		if (tab == null || tab.isMain() || !tabs.contains(tab)) {
			return false;
		}
		tabs.remove(tab);
		if (active == tab) {
			active = mainTab();
		}
		save();
		return true;
	}

	/**
	 * Меняет вкладки местами.
	 *
	 * @param from индекс в {@link #tabs()}
	 * @param to   индекс, на который вкладку надо поставить
	 * @return {@code true}, если порядок изменился
	 */
	public boolean move(int from, int to) {
		if (from < 0 || from >= tabs.size() || to < 0 || to >= tabs.size() || from == to) {
			return false;
		}
		if (tabs.get(from).isMain()) {
			// Основная вкладка всегда первая: её место — точка отсчёта в полоске.
			return false;
		}
		tabs.add(to, tabs.remove(from));
		save();
		return true;
	}

	/** Следующая или предыдущая видимая вкладка. */
	public ChatTab cycle(int step) {
		List<ChatTab> visible = visibleTabs();
		if (visible.isEmpty()) {
			return mainTab();
		}
		int index = visible.indexOf(activeTab());
		if (index < 0) {
			index = 0;
		}
		int next = Math.floorMod(index + step, visible.size());
		active = visible.get(next);
		return active;
	}

	/** Заменяет список вкладок целиком — при загрузке или сбросе. */
	public void replaceAll(List<ChatTab> loaded) {
		if (loaded == null || loaded.isEmpty()) {
			return;
		}
		tabs.clear();
		tabs.addAll(loaded);
		active = mainTab();
	}

	public void resetToDefaults() {
		tabs.clear();
		tabs.addAll(defaultTabs());
		active = mainTab();
		save();
	}

	/** Привязывает файл конфигурации и сразу подтягивает из него вкладки. */
	public void bindStorage(Storage value) {
		storage = value;
		List<ChatTab> loaded = value.read();
		if (loaded != null && !loaded.isEmpty()) {
			replaceAll(loaded);
		}
	}

	void save() {
		if (storage != null) {
			storage.write(tabs);
		}
	}
}