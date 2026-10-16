package nya2dtyan.makotochat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Клиентские команды для управления вкладками.
 *
 * <p>Команды дублируют горячие клавиши: бинды игрок может случайно не назначить,
 * а {@code /chattabs} работает всегда. Вкладки теперь свои, поэтому команда
 * выбора принимает имя вкладки или её номер в полоске — перечислять их заранее
 * в дереве команд нельзя.
 */
public final class ChatTabsCommands {

	private ChatTabsCommands() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher,
			net.minecraft.commands.CommandBuildContext context) {
		LiteralArgumentBuilder<FabricClientCommandSource> root = LiteralArgumentBuilder
				.<FabricClientCommandSource>literal("chattabs")
				.executes(source -> summary(source.getSource()));

		root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("next")
				.executes(source -> {
					ChatRouter.getInstance().cycleNext();
					return one(source.getSource());
				}));

		root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("prev")
				.executes(source -> {
					ChatRouter.getInstance().cyclePrevious();
					return one(source.getSource());
				}));

		// Выбор вкладки по имени или по номеру в полоске: вкладки создаёт игрок,
		// поэтому список заранее неизвестен.
		root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("tab")
				.then(RequiredArgumentBuilder
						.<FabricClientCommandSource, String>argument("имя", StringArgumentType.greedyString())
						.executes(source -> selectByName(source.getSource(),
								StringArgumentType.getString(source, "имя")))));

		root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
				.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("active")
						.executes(source -> {
							ChatRouter.getInstance().store().clear(ChatRouter.getInstance().activeTab());
							return one(source.getSource());
						}))
				.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("all")
						.executes(source -> {
							ChatRouter.getInstance().store().clearAll();
							return one(source.getSource());
						})));

		root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("overlay")
				.executes(source -> {
					ChatRouter.getInstance().toggleOverlay();
					return one(source.getSource());
				}));

		dispatcher.register(root);
	}

	/** Переключает на вкладку по имени (без учёта регистра) или по номеру. */
	private static int selectByName(FabricClientCommandSource source, String query) {
		List<ChatTab> tabs = TabConfig.getInstance().visibleTabs();
		String wanted = query.trim();

		try {
			int index = Integer.parseInt(wanted) - 1;
			if (index >= 0 && index < tabs.size()) {
				ChatRouter.getInstance().select(tabs.get(index));
				return one(source);
			}
		} catch (NumberFormatException notANumber) {
			// Ниже ищем по имени — это обычный случай, а не ошибка.
		}

		for (ChatTab tab : tabs) {
			if (tab.name().equalsIgnoreCase(wanted)) {
				ChatRouter.getInstance().select(tab);
				return one(source);
			}
		}
		source.sendError(Component.literal("Вкладка не найдена: " + wanted));
		return 0;
	}

	private static int one(FabricClientCommandSource source) {
		return 1;
	}

	/** Сводка в лог: активная вкладка и сколько сообщений накопила каждая. */
	private static int summary(FabricClientCommandSource source) {
		ChatRouter router = ChatRouter.getInstance();
		StringBuilder text = new StringBuilder("Вкладки чата — активная: ")
				.append(router.activeTab().name());

		List<ChatTab> tabs = TabConfig.getInstance().tabs();
		for (int i = 0; i < tabs.size(); i++) {
			ChatTab tab = tabs.get(i);
			text.append(" | ").append(i + 1).append(". ").append(tab.name())
					.append(tab.isVisible() ? "" : " (скрыта)")
					.append(": ").append(router.store().totalIn(tab));
		}

		String message = text.toString();
		ChatTabsMod.logger().info(message);
		source.sendFeedback(Component.literal(message));
		return 1;
	}
}