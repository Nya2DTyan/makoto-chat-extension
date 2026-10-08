package nya2dtyan.makotochat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Какие вкладки показывать в чате.
 *
 * <p>Состояние живёт отдельно от {@link ChatChannel}, потому что это ответ на
 * вопрос «что игрок хочет видеть», а не «где лежит сообщение»: выключенная
 * вкладка продолжает копить сообщения в {@link TabStore}, они просто не
 * показываются. Иначе переключение галочки обнуляло бы историю.
 *
 * <p><b>🔧 всегда включена</b> и не выключается: это вкладка настроек, а не канал
 * чата. Если выключить её последней, игрок потеряет доступ к меню и не сможет
 * включить вкладки обратно — поэтому её нельзя погасить, а «Город» и прочие
 * можно. Правило ровно то, о котором просили: останется одна 🔧.
 */
public final class TabSettings {

	private static TabSettings instance;

	/**
	 * Каналы, видимые в чате. 🔧 в наборе не участвует и всегда остаётся.
	 *
	 * <p>Наполняется перебором всех каналов, кроме 🔧, — так новая вкладка
	 * появляется в настройках сама. Список вручную однажды уже забыли обновить
	 * при добавлении канала, и вкладка просто не показывалась.
	 */
	private final Set<ChatChannel> enabled = allConfigurableChannels();

	/**
	 * Порядок вкладок в полоске: игрок перетаскивает их в меню настроек.
	 *
	 * <p>Хранится именно как список, а не как {@link Set}: множество не помнит
	 * порядок, и перетаскивание было бы негде хранить. Наполняется всеми
	 * каналами сразу, поэтому новая вкладка попадает в конец сама.
	 */
	private final List<ChatChannel> order = new ArrayList<>(allOrderedChannels());

	/** Все каналы чата, кроме служебной 🔧. */
	private static EnumSet<ChatChannel> allConfigurableChannels() {
		EnumSet<ChatChannel> all = EnumSet.noneOf(ChatChannel.class);
		for (ChatChannel channel : ChatChannel.values()) {
			if (channel != ChatChannel.SETTINGS) {
				all.add(channel);
			}
		}
		return all;
	}

	/**
	 * Все каналы в порядке объявления, включая служебную 🔧.
	 *
	 * <p>Порядок по умолчанию совпадает с порядком констант в
	 * {@link ChatChannel}, поэтому до первого перетаскивания полоска выглядит
	 * ровно как раньше.
	 */
	private static List<ChatChannel> allOrderedChannels() {
		return new ArrayList<>(java.util.Arrays.asList(ChatChannel.values()));
	}

	private TabSettings() {
	}

	public static synchronized TabSettings getInstance() {
		if (instance == null) {
			instance = new TabSettings();
		}
		return instance;
	}

	/**
	 * Вкладка 🔧 не является каналом чата, поэтому её нельзя выключить: иначе
	 * меню настроек стало бы недоступно и вернуть вкладки было бы нечем.
	 */
	public boolean isConfigurable(ChatChannel channel) {
		return channel != ChatChannel.SETTINGS;
	}

	/** Показывается ли вкладка в полоске и входит ли она в переключение по кругу. */
	public boolean isEnabled(ChatChannel channel) {
		return channel == ChatChannel.SETTINGS || enabled.contains(channel);
	}

	/** Переключает видимость вкладки. Для 🔧 ничего не делает. */
	public boolean toggle(ChatChannel channel) {
		if (!isConfigurable(channel)) {
			return false;
		}
		if (enabled.contains(channel)) {
			enabled.remove(channel);
			return false;
		}
		enabled.add(channel);
		return true;
	}

	public void setEnabled(ChatChannel channel, boolean value) {
		if (!isConfigurable(channel)) {
			return;
		}
		if (value) {
			enabled.add(channel);
		} else {
			enabled.remove(channel);
		}
	}

	/**
	 * Каналы в порядке, заданном игроком, но только видимые — и всегда с 🔧 в конце.
	 *
	 * <p>Полоску вкладок и переключение по кругу надо вести по этому списку, а не
	 * по {@link ChatChannel#values()}: иначе по нажатию клавиши курсор прыгал бы
	 * по выключенным вкладкам, которых на экране нет, и перетаскивание в меню
	 * настроек ничего бы не меняло.
	 *
	 * <p>🔧 всегда последняя: это служебная вкладка, а не канал чата, и держать
	 * её среди обычных вкладок незачем.
	 */
	public ChatChannel[] visibleChannels() {
		ChatChannel[] out = new ChatChannel[order.size()];
		int n = 0;
		for (ChatChannel channel : order) {
			if (isEnabled(channel)) {
				out[n++] = channel;
			}
		}
		return java.util.Arrays.copyOf(out, n);
	}

	/**
	 * Все каналы в заданном порядке, включая выключенные и служебную 🔧.
	 *
	 * <p>Это то, что рисует меню настроек: перетаскивать выключенную вкладку
	 * тоже надо, иначе вернуть её на место было бы нечем.
	 */
	public ChatChannel[] orderedChannels() {
		return order.toArray(new ChatChannel[0]);
	}

	/**
	 * Переносит вкладку на новое место.
	 *
	 * <p>Индекс — тот, что видит игрок в меню, то есть по {@link #orderedChannels()}.
	 * Служебную 🔧 двигать нельзя: она всегда последняя, и перетаскивание её
	 * вверх сделало бы вкладку настроек недоступной в привычном месте.
	 *
	 * @param from индекс вкладки в {@link #orderedChannels()}
	 * @param to   индекс, на который её надо поставить
	 * @return {@code true}, если порядок действительно изменился
	 */
	public boolean moveChannel(int from, int to) {
		if (from < 0 || from >= order.size() || to < 0 || to >= order.size() || from == to) {
			return false;
		}
		if (order.get(from) == ChatChannel.SETTINGS) {
			return false;
		}
		// Двигать 🔧 нельзя, поэтому она всегда остаётся последней: сдвигаем
		// целевой индекс внутрь списка обычных вкладок.
		int limit = order.size() - 1;
		if (to > limit) {
			to = limit;
		}
		order.add(to, order.remove(from));
		return true;
	}

	/** Следующая видимая вкладка по кругу. */
	public ChatChannel cycle(ChatChannel current, int step) {
		ChatChannel[] visible = visibleChannels();
		if (visible.length == 0) {
			return ChatChannel.SETTINGS;
		}
		int index = -1;
		for (int i = 0; i < visible.length; i++) {
			if (visible[i] == current) {
				index = i;
				break;
			}
		}
		// Текущая вкладка могла быть выключена — тогда начинаем с начала.
		if (index < 0) {
			return visible[0];
		}
		int next = (index + step) % visible.length;
		if (next < 0) {
			next += visible.length;
		}
		return visible[next];
	}
}