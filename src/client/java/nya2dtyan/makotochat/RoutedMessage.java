package nya2dtyan.makotochat;

import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;

/**
 * Сообщение, разложенное на вкладки.
 *
 * <p>Хранится один раз, а вкладки держат на него ссылки — так переключение
 * вкладок мгновенное, а память не растёт от числа вкладок.
 *
 * <p>У каждого сообщения есть «псевдоним» для каждого канала: одно и то же
 * сообщение может быть видно в нескольких вкладках (например, обычный чат,
 * где сервер печатает CoreProtect-строку в общий поток), но текст в каждой
 * вкладке свой — с подписью канала.
 */
public final class RoutedMessage {

	private final Component content;
	private final GuiMessage original;
	private final long timestamp;
	private final int lineOrder;

	public RoutedMessage(Component content, GuiMessage original, long timestamp, int lineOrder) {
		this.content = content;
		this.original = original;
		this.timestamp = timestamp;
		this.lineOrder = lineOrder;
	}

	public Component content() {
		return content;
	}

	/** Исходное сообщение ванильного чата — нужно, чтобы не сломать подписи и стили. */
	public GuiMessage original() {
		return original;
	}

	public long timestamp() {
		return timestamp;
	}

	/** Порядковый номер для стабильной сортировки (время может совпасть). */
	public int lineOrder() {
		return lineOrder;
	}

	public boolean sameAs(RoutedMessage other) {
		if (other == null) {
			return false;
		}
		if (original != null && other.original != null) {
			return original.equals(other.original);
		}
		return lineOrder == other.lineOrder && timestamp == other.timestamp;
	}
}