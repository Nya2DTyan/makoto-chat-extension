package nya2dtyan.makotochat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Хранит сообщения по вкладкам.
 *
 * <p>Главное свой��тво: <b>вкладки живут независимо</b>. Сообщение, пришедшее
 * пока открыта вкладка «Город», всё равно попадает в «ЛС», если пришло по ЛС —
 * переключение вкладки не «проматывает» пропущенные сообщения.
 *
 * <p>Внутри каждой вкладки — кольцевой буфер фиксированного размера: серверный
 * чат не должен расти без предела, а старые сообщения всё равно недоступны
 * игроку в ванильном чате.
 */
public final class TabStore {

	/** Сколько сообщений держим в каждой вкладке. */
	private static final int CAPACITY_PER_TAB = 500;

	private final Map<ChatChannel, ArrayDeque<RoutedMessage>> tabs = new EnumMap<>(ChatChannel.class);

	/** Монотонный счётчик для стабильного порядка при одинаковом времени. */
	private int lineOrderCounter;

	private ChatChannel activeTab = ChatChannel.GLOBAL;

	public TabStore() {
		for (ChatChannel channel : ChatChannel.values()) {
			tabs.put(channel, new ArrayDeque<>(CAPACITY_PER_TAB));
		}
	}

	/** Кладёт сообщение в указанную вкладку, вытесняя самое старое при переполнении. */
	public void add(ChatChannel channel, RoutedMessage message) {
		ArrayDeque<RoutedMessage> queue = tabs.get(channel);
		queue.addLast(message);
		while (queue.size() > CAPACITY_PER_TAB) {
			queue.removeFirst();
		}
	}

	/**
	 * Кладёт сообщение сразу в несколько вкладок (общий чат как источник,
	 * из которого часть строк относится к специфичным каналам).
	 */
	public void addToAll(ChatChannel primary, List<ChatChannel> alsoVisible, RoutedMessage message) {
		add(primary, message);
		for (ChatChannel channel : alsoVisible) {
			if (channel != primary) {
				add(channel, message);
			}
		}
	}

	public List<RoutedMessage> messages(ChatChannel channel) {
		return new ArrayList<>(tabs.get(channel));
	}

	/** Сколько непрочитанных сообщений вкладки накопила, пока открыта другая. */
	public int unreadCount(ChatChannel channel) {
		if (channel == activeTab) {
			return 0;
		}
		int unread = 0;
		for (RoutedMessage message : tabs.get(channel)) {
			if (message.timestamp() > lastReadAt(channel)) {
				unread++;
			}
		}
		return unread;
	}

	private final Map<ChatChannel, Long> lastRead = new EnumMap<>(ChatChannel.class);

	private long lastReadAt(ChatChannel channel) {
		return lastRead.getOrDefault(channel, 0L);
	}

	/** Помечает вкладку прочитанной — вызывается при переключении на неё. */
	public void markRead(ChatChannel channel) {
		long newest = 0L;
		ArrayDeque<RoutedMessage> queue = tabs.get(channel);
		RoutedMessage last = queue.peekLast();
		if (last != null) {
			newest = last.timestamp();
		}
		lastRead.put(channel, newest);
	}

	public ChatChannel activeTab() {
		return activeTab;
	}

	public void setActiveTab(ChatChannel channel) {
		activeTab = channel;
		markRead(channel);
	}

	/** Переключает вкладку по кругу — основной способ переключения. */
	public ChatChannel cycleNext() {
		ChatChannel[] all = ChatChannel.values();
		int index = activeTab.ordinal() + 1;
		ChatChannel next = all[index % all.length];
		setActiveTab(next);
		return next;
	}

	public ChatChannel cyclePrevious() {
		ChatChannel[] all = ChatChannel.values();
		int index = activeTab.ordinal() - 1 + all.length;
		ChatChannel next = all[index % all.length];
		setActiveTab(next);
		return next;
	}

	public int totalIn(ChatChannel channel) {
		return tabs.get(channel).size();
	}

	/** Очищает вкладку — отдельная команда на случай засорения. */
	public void clear(ChatChannel channel) {
		tabs.get(channel).clear();
	}

	public void clearAll() {
		for (ChatChannel channel : ChatChannel.values()) {
			tabs.get(channel).clear();
		}
		lineOrderCounter = 0;
	}

	public int nextLineOrder() {
		return lineOrderCounter++;
	}

	/** Сбрасывает счётчик порядка — только при полной очистке. */
	public void resetLineOrder() {
		lineOrderCounter = 0;
	}
}