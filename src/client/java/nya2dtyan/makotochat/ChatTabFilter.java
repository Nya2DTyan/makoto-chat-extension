package nya2dtyan.makotochat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Разделяет видимый чат по вкладкам через {@link ChatComponent#setVisibleMessageFilter}.
 *
 * <p><b>Главное, что пришлось выяснить по байткоду ванильного чата.</b> Фильтр
 * спрашивается ванильным чатом в двух местах, и это ломает наивную реализацию:
 *
 * <ul>
 *   <li>{@code addMessage(...)} — <b>в момент приёма</b>. Если фильтр ответил
 *       «не показывать», сообщение не попадает <i>ни</i> в историю
 *       {@code allMessages}, никуда: оно отброшено. Отсюда был симптом «счётчик
 *       показывает 1, а во вкладке пусто» — счётчик ведёт наше собственное
 *       хранилище, а ванильный чат про сообщение уже забыл;
 *   <li>{@code refreshTrimmedMessages()} — при пересборке списка отрисовки, где
 *       фильтр применяется к истории заново.
 * </ul>
 *
 * <p>Отсюда решение: <b>на приёме фильтр пропускает всё</b>, чтобы сообщение
 * гарантированно попало в историю и могло быть показано позже, на любой вкладке.
 * Разбиение по вкладкам происходит только при пересборке, которую инициирует
 * роутер: при смене вкладки или при поступлении нового сообщения. Флаг
 * {@link #rebuilding} отличает эти два вызова фильтра друг от друга.
 *
 * <p>Следствие ограничения API: пересборка идёт по истории чата, а она у ванилы
 * ограничена ста последними сообщениями, так что глубина видимой истории общая
 * на все вкладки. Счётчики непрочитанного живут в {@link TabStore} и этого
 * ограничения не имеют.
 *
 * <p><b>Решения берутся из роутера.</b> Ванильный чат отдаёт {@link GuiMessage}
 * без ссылки на наше хранилище, поэтому сопоставить сообщение с вкладкой можно
 * только по тексту. Классифицировать заново здесь нельзя: роутер уже решил, и
 * иногда решил иначе — например, отказ сервера он кладёт и в «Общий», и в
 * «CoreProtect». Поэтому роутер записывает свои решения через
 * {@link #record(String, ChatChannel)}, а фильтр их читать.
 */
public final class ChatTabFilter {

	/** Сколько текстов помним при сопоставлении. Больше, чем глубина чата. */
	private static final int REMEMBERED_TEXTS = 2000;

	private static ChatTabFilter instance;

	/** Текст (нормализованный) → во какие вкладки сообщение попало. */
	private final Map<String, Set<ChatChannel>> routingByText =
			new LinkedHashMap<>(256, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Set<ChatChannel>> eldest) {
					return size() > REMEMBERED_TEXTS;
				}
			};

	/**
	 * Истина, пока ванильный чат перебирает историю для пересборки.
	 *
	 * <p>В этот момент фильтр отвечает на вопрос «в активную ли вкладку попадёт
	 * строка»; в остальное время — на «впускать ли сообщение в историю», и ответ
	 * всегда «да». Флаг держим на всё время вызова, а не только на время прямой
	 * строки: пересборка рекурсивна, и вложенные вызовы тоже должны спрашивать.
	 */
	private boolean rebuilding;

	/** Требуется пересборка списка отрисовки. Ставится роутером, гасится в tick. */
	private boolean dirty;

	private boolean installed;

	private ChatTabFilter() {
	}

	public static synchronized ChatTabFilter getInstance() {
		if (instance == null) {
			instance = new ChatTabFilter();
		}
		return instance;
	}

	/** Ставит фильтр в ванильный чат. */
	public void install() {
		rebuild();
	}

	/**
	 * Просит пересобрать список отрисовки.
	 *
	 * <p>Пересборка не делается сразу: сообщение могло ещё не дойти до чата —
	 * порядок между событием Fabric и добавлением в {@code ChatComponent} не
	 * задан, — а пересборка должна видеть его уже в истории. Поэтому она
	 * привязана к следующему тику клиента.
	 */
	public void markDirty() {
		dirty = true;
	}

	/** Пересобирает список, если накопилось изменение. Вызывается каждый тик. */
	public void onClientTick() {
		if (dirty) {
			rebuild(false);
		}
	}

	/**
	 * Пересобирает список отрисованных строк под текущую вкладку.
	 *
	 * <p>Публичного API для пересборки нет, но {@code setVisibleMessageFilter}
	 * внутри вызывает приватный {@code refreshTrimmedMessages()}, поэтому повторная
	 * установка фильтра и есть штатный способ. Отражения и миксинов не нужно.
	 *
	 * @param toBottom {@code true} — показать последние сообщения (смена вкладки),
	 *     {@code false} — не трогать положение прокрутки (пришло новое сообщение)
	 */
	public void rebuild(boolean toBottom) {
		ChatComponent chat = chatComponent();
		if (chat == null) {
			// Чат ещё не создан (до входа в мир) — ставить некуда.
			return;
		}
		rebuilding = true;
		try {
			chat.setVisibleMessageFilter(this::isVisible);
		} finally {
			rebuilding = false;
		}
		dirty = false;
		if (toBottom) {
			// После пересборки список строк меняется, и вид остался бы в пустоте.
			chat.resetChatScroll();
		}
		installed = true;
		ChatTabsMod.logger().debug("Фильтр вкладок установлен, список отрисовки пересобран");
	}

	/** Пересобирает список и показывает последние сообщения — при смене вкладки. */
	public void rebuild() {
		rebuild(true);
	}

	/**
	 * Запоминает решение роутера: этот текст виден в таких-то вкладках.
	 *
	 * <p>Несколько вкладок накапливаются в одном наборе — так повторная запись
	 * не теряет предыдущие каналы (например, команда CoreProtect видна и в
	 * «Общий», и в «CO»).
	 */
	public void record(String rawText, ChatChannel channel) {
		if (rawText == null || rawText.isBlank()) {
			return;
		}
		routingByText.compute(ChatChannel.normalize(rawText), (key, existing) -> {
			Set<ChatChannel> result = existing == null ? EnumSet.noneOf(ChatChannel.class)
					: EnumSet.copyOf(existing);
			result.add(channel);
			return result;
		});
	}

	/**
	 * Решение ванильного чата: показывать ли сообщение.
	 *
	 * <p>На приёме — всегда «да», иначе сообщение потерялось бы из истории и не
	 * появилось бы ни в одной вкладке. При пересборке — по активной вкладке.
	 *
	 * <p>Ванильный чат рисуется сам, и трогать его рендер нельзя: собственный
	 * отрисовщик строк ломал и геометрию, и позиционирование.
	 */
	private boolean isVisible(GuiMessage message) {
		if (!rebuilding) {
			return true;
		}
		String text = message.content() == null ? "" : message.content().getString();
		return inActiveTab(text);
	}

	/**
	 * Попадает ли строка с таким текстом в активную вкладку.
	 *
	 * <p>«Общий» показывает <b>всё</b>, включая ЛС, город и вывод CoreProtect:
	 * это запасной чат, в котором ничего не теряется. Специфичные вкладки —
	 * выборки из него, а не его подмножества.
	 */
	public boolean inActiveTab(String text) {
		ChatChannel active = ChatRouter.getInstance().activeTab();
		if (active == ChatChannel.GLOBAL) {
			return true;
		}
		return channelsOf(text).contains(active);
	}

	/** В какие вкладки попадает сообщение с таким текстом. */
	public Set<ChatChannel> channelsOf(String text) {
		String key = ChatChannel.normalize(text);
		Set<ChatChannel> recorded = routingByText.get(key);
		if (recorded != null) {
			return recorded;
		}
		// Сообщения, которых роутер не видел (служебные строки клиента),
		// классифицируем сами — иначе они исчезли бы из всех вкладок.
		return EnumSet.of(MessageClassifier.classify(text, false));
	}

	/** Сбрасывает сопоставления и требует пересборки — например, при смене мира. */
	public void invalidateCache() {
		routingByText.clear();
		dirty = true;
	}

	/** Принудительно переустановить фильтр — после смены мира. */
	public void refresh() {
		invalidateCache();
		rebuild(true);
	}

	/** @return {@code true}, если фильтр реально установлен в ванильном чате */
	public boolean isInstalled() {
		return installed;
	}

	private static ChatComponent chatComponent() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.gui == null || minecraft.gui.hud == null) {
			return null;
		}
		// getChat() публичный — аксессор-миксин не нужен.
		return minecraft.gui.hud.getChat();
	}
}