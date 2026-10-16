package nya2dtyan.makotochat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Хранит сообщения по вкладкам.
 *
 * <p>Главное свойство: <b>вкладки живут независимо</b>. Сообщение, пришедшее
 * пока открыта вкладка «Город», всё равно попадает в «ЛС», если пришло по ЛС —
 * переключение вкладки не «проматывает» пропущенные сообщения.
 *
 * <p>Ключ — сама вкладка ({@link ChatTab}), а не тип сообщения: вкладок теперь
 * может быть сколько угодно и с любым набором категорий, поэтому одну реплику
 * кладут во все вкладки, которые её показывают, — по одной записи на вкладку.
 * Вкладки сравниваются по ссылке, и это здесь правильно: две вкладки с
 * одинаковым названием — всё равно разные вкладки.
 *
 * <p>Внутри каждой вкладки — кольцевой буфер фиксированного размера: серверный
 * чат не должен расти без предела, а старые сообщения всё равно недоступны
 * игроку в ванильном чате.
 */
public final class TabStore {

	/** Сколько сообщений держим в каждой вкладке. */
	private static final int CAPACITY_PER_TAB = 500;

	private final Map<ChatTab, ArrayDeque<RoutedMessage>> tabs = new HashMap<>();

	/** Когда вкладку в последний раз смотрели — по этому считаются непрочитанные. */
	private final Map<ChatTab, Long> lastRead = new HashMap<>();

	/** Монотонный счётчик для стабильного порядка при одинаковом времени. */
	private int lineOrderCounter;

	/** Кладёт сообщение во вкладку, вытесняя самое старое при переполнении. */
	public void add(ChatTab tab, RoutedMessage message) {
		ArrayDeque<RoutedMessage> queue = tabs.computeIfAbsent(tab,
				key -> new ArrayDeque<>(CAPACITY_PER_TAB));
		queue.addLast(message);
		while (queue.size() > CAPACITY_PER_TAB) {
			queue.removeFirst();
		}
	}

	public List<RoutedMessage> messages(ChatTab tab) {
		ArrayDeque<RoutedMessage> queue = tabs.get(tab);
		return queue == null ? List.of() : new ArrayList<>(queue);
	}

	/**
	 * Сколько непрочитанных накопила вкладка, пока открыта другая.
	 *
	 * @param active активная вкладка: её сообщения считаются прочитанными
	 */
	public int unreadCount(ChatTab tab, ChatTab active) {
		if (tab == active) {
			return 0;
		}
		ArrayDeque<RoutedMessage> queue = tabs.get(tab);
		if (queue == null) {
			return 0;
		}
		long readAt = lastReadAt(tab);
		int unread = 0;
		for (RoutedMessage message : queue) {
			if (message.timestamp() > readAt) {
				unread++;
			}
		}
		return unread;
	}

	private long lastReadAt(ChatTab tab) {
		return lastRead.getOrDefault(tab, 0L);
	}

	/** Помечает вкладку прочитанной — вызывается при переключении на неё. */
	public void markRead(ChatTab tab) {
		long newest = 0L;
		ArrayDeque<RoutedMessage> queue = tabs.get(tab);
		RoutedMessage last = queue == null ? null : queue.peekLast();
		if (last != null) {
			newest = last.timestamp();
		}
		lastRead.put(tab, newest);
	}

	public int totalIn(ChatTab tab) {
		ArrayDeque<RoutedMessage> queue = tabs.get(tab);
		return queue == null ? 0 : queue.size();
	}

	/** Очищает вкладку — отдельная команда на случай засорения. */
	public void clear(ChatTab tab) {
		ArrayDeque<RoutedMessage> queue = tabs.get(tab);
		if (queue != null) {
			queue.clear();
		}
	}

	public void clearAll() {
		tabs.clear();
		lastRead.clear();
		lineOrderCounter = 0;
	}

	/**
	 * Забывает удалённую вкладку.
	 *
	 * <p>Без этого её сообщения остались бы в памяти до конца сессии: удалять
	 * вкладку можно в любой момент, а ключ-ссылка держала бы её живой.
	 */
	public void forget(ChatTab tab) {
		tabs.remove(tab);
		lastRead.remove(tab);
	}

	/** Забывает всё, чего нет в списке живых вкладок. */
	public void retainOnly(List<ChatTab> alive) {
		tabs.keySet().retainAll(alive);
		lastRead.keySet().retainAll(alive);
	}

	public int nextLineOrder() {
		return lineOrderCounter++;
	}

	/** Сбрасывает счётчик порядка — только при полной очистке. */
	public void resetLineOrder() {
		lineOrderCounter = 0;
	}
}