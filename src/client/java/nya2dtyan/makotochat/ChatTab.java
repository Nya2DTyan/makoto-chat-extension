package nya2dtyan.makotochat;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Вкладка игрока: своё название и набор категорий сообщений.
 *
 * <p>Вкладка — это фильтр, а не хранилище: сообщение попадает в неё, если его
 * категория входит в набор вкладки. Из этого сразу следуют обе возможности,
 * о которых просили: можно собрать вкладку из нескольких категорий (например,
 * «Тикеты и ЛС»), а можно убрать категорию из основной вкладки — тогда такие
 * сообщения останутся только там, где эта категория отмечена.
 *
 * <p>Основная вкладка (та, что была «Общим» чатом) помечена флагом
 * {@link #isMain()}: её нельзя удалить, иначе игрок потеряет вкладку, в которой
 * видно всё остальное. Редактировать её при этом можно — категории в ней те же
 * галочки, что и у любой другой.
 */
public final class ChatTab {

	/** Какую часть названия показываем в полоске под чатом. */
	static final int LABEL_LIMIT = 12;

	private final boolean main;

	private String name;

	/** Категории, которые вкладка показывает. Пустой набор — вкладка пустая. */
	private final EnumSet<MessageCategory> categories;

	private boolean visible = true;

	ChatTab(String name, Set<MessageCategory> categories, boolean main) {
		this.name = name == null || name.isBlank() ? "Вкладка" : name.trim();
		this.categories = categories.isEmpty()
				? EnumSet.noneOf(MessageCategory.class)
				: EnumSet.copyOf(categories);
		this.main = main;
	}

	/** Основная вкладка: содержит всё по умолчанию и не удаляется. */
	public boolean isMain() {
		return main;
	}

	public String name() {
		return name;
	}

	/** Пустое имя не принимаем: вкладку без подписи нельзя отличить от соседней. */
	public void setName(String value) {
		if (value != null && !value.isBlank()) {
			name = value.trim();
		}
	}

	public Set<MessageCategory> categories() {
		return Collections.unmodifiableSet(categories);
	}

	public boolean has(MessageCategory category) {
		return categories.contains(category);
	}

	public int categoryCount() {
		return categories.size();
	}

	/** Ставит или снимает галочку категории. */
	public void setCategory(MessageCategory category, boolean value) {
		if (value) {
			categories.add(category);
		} else {
			categories.remove(category);
		}
	}

	/** Показывается ли вкладка в полоске под чатом. */
	public boolean isVisible() {
		return visible;
	}

	public void setVisible(boolean value) {
		visible = value;
	}

	/**
	 * Попадает ли сообщение с таким набором категорий в эту вкладку.
	 *
	 * <p>Пересечение, а не вхождение: у сообщения бывает больше одной категории,
	 * и достаточно совпадения по любой из них.
	 */
	public boolean showsAny(Set<MessageCategory> messageCategories) {
		for (MessageCategory category : messageCategories) {
			if (categories.contains(category)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Подпись для полоски под чатом.
	 *
	 * <p>Длинное имя обрезаем: полоска не переносится и на узком экране
	 * растянулась бы за край.
	 */
	public String label() {
		if (name.length() <= LABEL_LIMIT) {
			return name;
		}
		return name.substring(0, LABEL_LIMIT);
	}
}