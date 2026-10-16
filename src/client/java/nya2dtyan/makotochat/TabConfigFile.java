package nya2dtyan.makotochat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Хранит вкладки игрока в файле конфигурации: {@code config/makoto-chat-extension.json}.
 *
 * <p>Без этого вкладки жили бы до перезапуска игры, а игрок называет их своим
 * именем и настраивает категории — терять эту работу при каждом запуске нельзя.
 *
 * <p>Файл читается мягко: испорченный или чужой файл не должен мешать игре
 * начатьcя — в этом случае возвращаем {@code null}, и модель берёт вкладки по
 * умолчанию. Так же поступаем, если в файле не осталось основной вкладки.
 */
public final class TabConfigFile implements TabConfig.Storage {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path path;

	public TabConfigFile() {
		this(FabricLoader.getInstance().getConfigDir().resolve("makoto-chat-extension.json"));
	}

	/** Отдельный конструктор с путём — для проверок без запуска игры. */
	public TabConfigFile(Path path) {
		this.path = path;
	}

	/** Что лежит в файле. Поля простые: так формат переживает смену версий. */
	static final class TabDto {

		String name;
		boolean main;
		boolean visible = true;
		List<String> categories = new ArrayList<>();
	}

	@Override
	public List<ChatTab> read() {
		try {
			if (!Files.isRegularFile(path)) {
				return null;
			}
			List<TabDto> dtos = GSON.fromJson(Files.readString(path),
					new TypeToken<List<TabDto>>() {
					}.getType());
			if (dtos == null || dtos.isEmpty()) {
				return null;
			}

			List<ChatTab> tabs = new ArrayList<>();
			boolean mainSeen = false;
			for (TabDto dto : dtos) {
				if (tabs.size() >= TabConfig.MAX_TABS) {
					break;
				}
				if (dto == null || dto.name == null || dto.name.isBlank()) {
					continue;
				}
				// Основная вкладка в файле должна быть одна: если её нет или их
				// несколько, первую объявляем основной, остальные — обычными.
				boolean main = dto.main && !mainSeen;
				mainSeen |= main;

				EnumSet<MessageCategory> categories = EnumSet.noneOf(MessageCategory.class);
				if (dto.categories != null) {
					for (String name : dto.categories) {
						try {
							categories.add(MessageCategory.valueOf(name));
						} catch (IllegalArgumentException unknown) {
							// Категорию убрали из мода — просто пропускаем её.
						}
					}
				}
				tabs.add(new ChatTab(dto.name, categories, main));
			}

			if (tabs.isEmpty()) {
				return null;
			}
			if (!mainSeen) {
				TabDto first = dtos.get(0);
				tabs.set(0, new ChatTab(tabs.get(0).name(), tabs.get(0).categories(), true));
				ChatTabsMod.logger().warn("В конфигурации вкладок не было основной — назначил «{}»",
						first == null ? tabs.get(0).name() : first.name);
			}
			return tabs;
		} catch (Exception e) {
			ChatTabsMod.logger().warn("Не удалось прочитать вкладки из {}: {}", path, e.toString());
			return null;
		}
	}

	@Override
	public void write(List<ChatTab> tabs) {
		try {
			List<TabDto> dtos = new ArrayList<>();
			for (ChatTab tab : tabs) {
				TabDto dto = new TabDto();
				dto.name = tab.name();
				dto.main = tab.isMain();
				dto.visible = tab.isVisible();
				for (MessageCategory category : tab.categories()) {
					dto.categories.add(category.name());
				}
				dtos.add(dto);
			}
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(dtos));
		} catch (Exception e) {
			// Не сохранилось — не повод ломать игру: вкладки просто не переживут
			// перезапуск, и об этом честно пишем в лог.
			ChatTabsMod.logger().warn("Не удалось сохранить вкладки в {}: {}", path, e.toString());
		}
	}
}